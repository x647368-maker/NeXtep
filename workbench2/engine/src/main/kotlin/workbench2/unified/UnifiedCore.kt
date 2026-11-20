package workbench2.unified

import workbench2.motion.*

/** Offline reference only. The backend must authenticate all evidence independently. */
data class Capability(val firmware:String,val hostVersion:String,val host:Boolean,val symbols:Boolean,
                      val authorized:Boolean,val embedding:Boolean,val input:Boolean,val release:Boolean,val firmwareMatches:Boolean) {
    fun usable()=firmware.isNotBlank() && hostVersion.isNotBlank() && host && symbols && authorized && embedding && input && release && firmwareMatches
}
enum class Phase { CLOSED, STARTING, VERIFY_VISUAL, VERIFY_FOCUS, ACTIVE, BLOCK_INPUT, FLYING, DRAINING, RECOVERY }
enum class Kind { HOST, TASK_LEASE, BORROWED_PRESENTATION, OBSERVER, INPUT_GATE }
/** Request identity is not a task ID. The adapter must scope it to Effect.epoch + host instance. */
data class LaunchRequest(val id:Long,val user:Int,val component:String,val existing:Owner?=null) {
    init { require(id>0 && user>=0 && component.isNotBlank() && (existing==null || existing.user==user)) }
}
data class StartPlan(val requests:List<LaunchRequest>,val places:List<Box>,val displayEpoch:Long) {
    init { require(requests.size in 1..5 && places.size==requests.size && displayEpoch>=0 && requests.map{it.id}.distinct().size==requests.size) }
}
/** The backend must independently establish the request/container relationship and terminality.
 * "terminal" means no later creation can occur for this exact operation, not a Binder ACK. */
/** Independent system-side observation of the original borrowed task, scoped to this
 * request, host and effect by the adapter. A missing task, transition enqueue, or a
 * container becoming empty is not proof. These fields are not self-authenticating. */
data class TaskReturnProof(val task:Int,val user:Int,val token:Long,
                           val originalOwnershipRestored:Boolean,val noPendingReattach:Boolean)
data class AttachProof(val requestId:Long,val container:String?,val actualOwner:Owner?,val actualComponent:String?,val correlated:Boolean,val terminal:Boolean,
                       val returnedTask:TaskReturnProof?=null)
data class Resource(val kind:Kind,val owner:Owner?=null,val request:LaunchRequest?=null,val container:String?=null)
enum class Op { ACQUIRE, RELEASE, QUIESCE, BLOCK, FRAME, VERIFY_VISUAL, VERIFY_FOCUS }
enum class Observation { PRESENT, ABSENT, UNKNOWN }
data class Effect(val epoch:Long,val serial:Long,val op:Op,val resource:Resource?=null,
                  val generation:Long=0,val progress:Double?=null,val expected:Scene?=null)
data class Evidence(val effect:Effect,val settled:Boolean,val observation:Observation=Observation.UNKNOWN,
                    val runtimeVerified:Boolean=false,val observedScene:Scene?=null,
                    val focused:Owner?=null,val blocked:Boolean=false,val attach:AttachProof?=null,
                    val inputBlockedObserved:Boolean?=null)

/** Exactly one reducer, one thread owner, one outstanding backend effect.
 * No worker or timer is created here. Android adapters own real cancellation/receipts.
 * Resource order: host -> input gate -> per-task return obligation -> borrowed (NOT native owning)
 * presentation references -> ordinary observer. The gate stays until tasks return. A quiescence barrier
 * precedes cleanup, and the host retains the independent cleanup receipt channel.
 */
class UnifiedCore {
    private val ownerThread=Thread.currentThread()
    private fun owner()=check(Thread.currentThread()===ownerThread)
    var phase=Phase.CLOSED; private set
    var epoch=0L; private set
    private var serial=0L
    private var generation=0L
    private var scene:Scene?=null
    private var flight:Flight?=null
    private var requested:Owner?=null
    private var progress=0.0
    private var queuedProgress:Double?=null
    private var pending:Effect?=null
    private var order=listOf<Resource>()
    private var plan:StartPlan?=null
    private val held=linkedSetOf<Resource>()
    private var stopping=false
    private var quiesced=false
    private var blockedRecovery=false
    fun current():Scene? { owner();return scene }
    fun outstanding():Effect? { owner();return pending }
    fun resources():Set<Resource> { owner();return held.toSet() }
    /** Register all creation obligations before issuing the first native operation.
     * No placeholder Owner or scene exists while tasks are being created. */
    fun beginPending(c:Capability,initial:StartPlan):Boolean {
        owner();if(phase!=Phase.CLOSED || !c.usable())return false
        check(held.isEmpty() && pending==null)
        val p=initial.copy(requests=java.util.Collections.unmodifiableList(ArrayList(initial.requests)),places=java.util.Collections.unmodifiableList(ArrayList(initial.places)))
        epoch++;generation++;scene=null;plan=p
        order=listOf(Resource(Kind.HOST),Resource(Kind.INPUT_GATE))+p.requests.flatMap { listOf(Resource(Kind.TASK_LEASE,request=it),Resource(Kind.BORROWED_PRESENTATION,request=it)) }+listOf(Resource(Kind.OBSERVER))
        stopping=false;quiesced=false;blockedRecovery=false;phase=Phase.STARTING;return true
    }
    private fun buildScene():Scene? {
        val p=plan?:return scene
        val owners=p.requests.map { req -> order.firstOrNull { it.kind==Kind.TASK_LEASE && it.request==req }?.owner?:return null }
        return Scene(owners,p.places,p.displayEpoch).frozenSnapshot()
    }
    /** Called only while a creation/return effect is outstanding. No guessed ownership. */
    private fun attachProofValid(e:Effect,r:Evidence):Boolean {
        val resource=e.resource?:return true
        if(resource.kind!=Kind.TASK_LEASE)return true
        val req=resource.request?:return false
        val v=r.attach?:return false
        if(!r.runtimeVerified || !v.correlated || !v.terminal || v.requestId!=req.id)return false
        if(resource.container!=null && v.container!=resource.container)return false
        if(r.observation==Observation.ABSENT) {
            if(v.actualOwner!=null)return false
            // A borrowed task must still exist in its original WM ownership. Apply this
            // to cancelled/failed acquire as well as release: either can leave a task
            // detached, deleted or awaiting an old reparent. New-task requests have
            // no pre-existing identity; their terminal creation/cleanup proof is unchanged.
            val original=req.existing?:return true
            val returned=v.returnedTask?:return false
            return returned.task==original.task && returned.user==original.user &&
                returned.token==original.token && returned.originalOwnershipRestored &&
                returned.noPendingReattach
        }
        if(r.observation!=Observation.PRESENT || v.container.isNullOrBlank())return false
        // A second request cannot borrow the same native container or task/token. Uncertain attribution
        // requires authoritative settlement, not an automatic return of somebody else's task.
        if(e.op==Op.ACQUIRE && held.any { h -> h.kind==Kind.TASK_LEASE && (h.container==v.container || (v.actualOwner!=null && h.owner?.let{it.task==v.actualOwner.task || it.token==v.actualOwner.token}==true)) })return false
        return true
    }
    /** Presence of the gate does not prove input is actually blocked; absence
     * does not prove it reopened. An unknown observation never settles the lease. */
    private fun inputGateProofValid(e:Effect,r:Evidence):Boolean {
        if(e.resource?.kind!=Kind.INPUT_GATE)return true
        return when(r.observation) {
            Observation.PRESENT -> r.inputBlockedObserved==true
            Observation.ABSENT -> r.inputBlockedObserved==false
            Observation.UNKNOWN -> false
        }
    }
    /** Existing-task admission compares identity separately from presentation. This is only
     * a consistency check after independent, terminal, host-scoped attach evidence.
     * Tokens must identify the same real task; generations never go backwards.
     * A changed canvas needs a newer content generation, even when the leash is unchanged.
     * Keep full Owner equality for scene, focus, animation and resource comparisons. */
    private fun sameExistingTask(previous:Owner,actual:Owner):Boolean =
        previous.task==actual.task && previous.user==actual.user && previous.token==actual.token &&
            actual.leashEpoch>=previous.leashEpoch &&
            actual.canvas.contentEpoch>=previous.canvas.contentEpoch &&
            (actual.canvas==previous.canvas || actual.canvas.contentEpoch>previous.canvas.contentEpoch)

    private fun adopt(e:Effect,r:Evidence) {
        val old=e.resource!!;val req=old.request
        if(old.kind!=Kind.TASK_LEASE || req==null){held.add(old);return}
        val v=r.attach!!;val a=v.actualOwner
        val valid=a!=null && a.user==req.user && v.actualComponent==req.component && (req.existing==null || sameExistingTask(req.existing,a))
        // Even an invalid but positively correlated creation has a return obligation. Keep only
        // the request and observed container; don't adopt unrelated identity into the scene.
        val actual=old.copy(owner=if(valid)a else null,container=v.container)
        val index=order.indexOf(old);check(index>=0)
        order=order.toMutableList().also { it[index]=actual; if(valid)it[index+1]=it[index+1].copy(owner=a,container=v.container) }.toList()
        held.add(actual)
        if(!valid)drain()
    }
    private fun issue(op:Op,r:Resource?=null,p:Double?=null,s:Scene?=null):Effect =
        // All expected scenes originate from buildScene or the frozen Flight target.
        // Reuse immutable identity across effects; independent receipt checks remain unchanged.
        Effect(epoch,++serial,op,r,generation,p,s).also { pending=it }
    /** No automatic idle polling; adapters call only on relevant events. */
    fun next():Effect? {
        owner();if(pending!=null || blockedRecovery)return null
        return when(phase) {
            Phase.STARTING -> {
                val r=order.firstOrNull { it !in held }
                if(r!=null)issue(Op.ACQUIRE,r) else { scene=buildScene();check(scene!=null);phase=Phase.VERIFY_VISUAL;issue(Op.VERIFY_VISUAL,s=scene) }
            }
            Phase.VERIFY_VISUAL -> issue(Op.VERIFY_VISUAL,s=flight?.to?:scene)
            Phase.VERIFY_FOCUS -> issue(Op.VERIFY_FOCUS,s=flight?.to?:scene)
            Phase.BLOCK_INPUT -> issue(Op.BLOCK,s=scene)
            Phase.FLYING -> queuedProgress?.let { p -> queuedProgress=null;issue(Op.FRAME,p=p,s=flight!!.to) }
            Phase.DRAINING -> {
                if(!quiesced && held.isNotEmpty())issue(Op.QUIESCE)
                else { val r=order.lastOrNull { it in held }
                    if(r!=null)issue(Op.RELEASE,r) else { phase=Phase.CLOSED;scene=null;flight=null;order=emptyList();requested=null;plan=null;null } }
            }
            else -> null
        }
    }
    fun request(o:Owner):Boolean {
        owner();val s=scene?:return false
        if(stopping || o !in s.owners)return false
        if(phase in setOf(Phase.BLOCK_INPUT,Phase.FLYING,Phase.VERIFY_VISUAL,Phase.VERIFY_FOCUS) && flight!=null) { requested=o;return false }
        if(phase!=Phase.ACTIVE)return false
        val i=s.owners.indexOf(o);if(i !in 1 until s.owners.size)return false
        generation++;flight=Flight(generation,s,i,24.0,12.0);progress=0.0;queuedProgress=null;phase=Phase.BLOCK_INPUT;return true
    }
    /** One pending progress value; don't queue hundreds of frames behind a slow backend. */
    fun frame(p:Double):Boolean {
        owner(); if(phase!=Phase.FLYING || !p.isFinite() || p !in 0.0..1.0 || p<progress || (queuedProgress?.let { p<it }==true) || (pending?.progress?.let {p<it}==true))return false
        if(!serialFrameStarted && pending?.op!=Op.FRAME)return false // exact initial frame issued first
        if(p==progress || p==pending?.progress || p==queuedProgress)return false
        queuedProgress=p;return true
    }
    private var serialFrameStarted=false
    fun pose(e:Effect):Map<Owner,Pose>? { owner();if(pending!=e || e.op!=Op.FRAME || phase!=Phase.FLYING)return null;return flight!!.sample(e.progress!!) }
    fun stop() {
        owner();if(phase==Phase.CLOSED)return
        stopping=true;requested=null;queuedProgress=null;flight=null
        if(phase!=Phase.RECOVERY)phase=Phase.DRAINING
    }
    fun timeout(e:Effect):Boolean {
        owner();if(pending!=e)return false
        stop();blockedRecovery=true;phase=Phase.RECOVERY;return true
    }
    private fun holdForRecovery(){stop();blockedRecovery=true;phase=Phase.RECOVERY}
    private fun drain(){stopping=true;requested=null;queuedProgress=null;flight=null;phase=Phase.DRAINING}
    fun receipt(r:Evidence):Boolean {
        owner();val e=pending?:return false;if(e!=r.effect)return false
        // A timeout does not settle an effect. Retain ownership even if the latest snapshot looks absent.
        if(!r.settled || ((e.op==Op.ACQUIRE||e.op==Op.RELEASE)&&(!r.runtimeVerified||r.observation==Observation.UNKNOWN||!attachProofValid(e,r)||!inputGateProofValid(e,r)))) { holdForRecovery();return true }
        pending=null;blockedRecovery=false
        when(e.op) {
            Op.ACQUIRE -> { if(r.observation==Observation.PRESENT)adopt(e,r) else drain() }
            Op.RELEASE -> { if(r.observation==Observation.ABSENT)held.remove(e.resource!!) else {holdForRecovery();return true} }
            Op.QUIESCE -> { if(!r.runtimeVerified||!r.blocked||r.inputBlockedObserved!=true){holdForRecovery();return true};quiesced=true }
            else -> {}
        }
        // Late settled acknowledgement after cancellation resolves the outstanding effect only.
        if(stopping){ phase=Phase.DRAINING;return true }
        when(e.op) {
            Op.ACQUIRE -> phase=Phase.STARTING
            Op.BLOCK -> if(r.runtimeVerified&&r.blocked&&r.inputBlockedObserved==true){phase=Phase.FLYING;serialFrameStarted=false;queuedProgress=0.0}else drain()
            Op.FRAME -> if(r.runtimeVerified && r.blocked && r.inputBlockedObserved==true){progress=e.progress!!;serialFrameStarted=true;if(progress==1.0){queuedProgress=null;phase=Phase.VERIFY_VISUAL}}else drain()
            Op.VERIFY_VISUAL -> if(r.runtimeVerified && r.blocked && r.inputBlockedObserved==true && r.observedScene==e.expected)phase=Phase.VERIFY_FOCUS else drain()
            Op.VERIFY_FOCUS -> if(r.runtimeVerified && r.observedScene==e.expected && r.focused==e.expected!!.owners[0] && !r.blocked && r.inputBlockedObserved==false){
                scene=e.expected;flight=null;phase=Phase.ACTIVE
                val q=requested;requested=null;if(q!=null)request(q)
            }else drain()
            else -> error("Unexpected active effect $e")
        }
        return true
    }
    /** Explicit bounded operator/backend recovery, not a retry loop. An uncertain effect cannot be retried. */
    fun retryCleanup():Boolean { owner();if(phase!=Phase.RECOVERY||pending!=null||!blockedRecovery)return false;blockedRecovery=false;phase=Phase.DRAINING;return true }
    fun invariant(){owner();check(order.take(held.size).toSet()==held);check(pending==null||pending!!.epoch==epoch)
        if(phase==Phase.CLOSED)check(held.isEmpty()&&pending==null&&scene==null&&plan==null)
        if(plan!=null && phase==Phase.STARTING)check(scene==null)
        if(phase==Phase.ACTIVE)check(held.size==order.size&&pending==null&&flight==null&&!stopping)
        if(phase==Phase.RECOVERY)check(stopping&&blockedRecovery)
        if(stopping)check(queuedProgress==null&&flight==null&&requested==null)
        if(phase==Phase.FLYING)check(flight!=null&&!stopping)
    }
}
