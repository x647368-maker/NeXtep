package workbench2.nativebridge
import workbench2.motion.*
import workbench2.unified.*

/** Runtime snapshots must come from independent host/WM observations. No expected scene may be
 * echoed as evidence. The OEM implementation is deliberately not fabricated in this reference. */
data class NativeSnapshot(val firmware:String,val hostVersion:String,val hostInstance:String,val scene:Scene?,val focused:Owner?,
                          val inputBlocked:Boolean?,val resource:Observation,
                          val settled:Boolean,val submissionVerified:Boolean,val contentReady:Boolean,
                          val callbacksStopped:Boolean=false,val attach:AttachProof?=null)
interface NativePort {
    /** Runs asynchronously when needed; completion must be marshalled to the owner Looper. */
    fun dispatch(effect:Effect,poses:Map<Owner,Pose>?,complete:(NativeSnapshot)->Unit)
    /** Disable our local UI callback sources immediately, NOT global Android input. */
    fun stopLocalCallbacks()
    /** Best-effort asynchronous cancellation for this exact creation, if OEM supports it.
     * Must preserve the original settlement callback; cancellation acceptance is NOT settlement. */
    fun requestAttachCancellation(effect:Effect) {}
}
class ReceiptBridge(private val core:UnifiedCore,private val port:NativePort,private val firmware:String,private val hostVersion:String,private val hostInstance:String){
    init { require(firmware.isNotBlank() && hostVersion.isNotBlank() && hostInstance.isNotBlank()) }
    private val owner=Thread.currentThread()
    private fun checkOwner(){check(Thread.currentThread()===owner)}
    private var dispatched:Effect?=null
    private var cancelRequested:Effect?=null
    private fun cancelPendingAttach(){
        val e=dispatched?:return
        if(e.op!=Op.ACQUIRE||e.resource?.kind!=Kind.TASK_LEASE||e.resource.request==null||cancelRequested==e)return
        cancelRequested=e
        try{port.requestAttachCancellation(e)}catch(_:Exception){ /* original effect remains outstanding */ }
    }
    fun pump():Boolean{
        checkOwner();if(dispatched!=null)return false
        val e=core.next()?:return false;dispatched=e
        try{port.dispatch(e,core.pose(e)){s->receive(e,s)}}catch(ex:Exception){if(dispatched==e){core.timeout(e);port.stopLocalCallbacks();cancelPendingAttach()}}
        return true
    }
    fun stop(){checkOwner();core.stop();port.stopLocalCallbacks();cancelPendingAttach()}
    fun timeout(e:Effect):Boolean{checkOwner();if(dispatched!=e)return false;val r=core.timeout(e);if(r){port.stopLocalCallbacks();cancelPendingAttach()};return r}
    private fun receive(e:Effect,s:NativeSnapshot){
        checkOwner();if(dispatched!=e)return
        // Different host instance/build => no trustworthy settlement receipt. Keep ownership.
        if(s.firmware!=firmware||s.hostVersion!=hostVersion||s.hostInstance!=hostInstance){core.timeout(e);port.stopLocalCallbacks();return}
        val obs=s.resource
        val verified=when(e.op){
            Op.ACQUIRE,Op.RELEASE->s.submissionVerified
            Op.BLOCK->s.inputBlocked==true&&s.submissionVerified
            Op.FRAME->s.submissionVerified&&s.inputBlocked==true // only submission, never presentation
            Op.QUIESCE->s.callbacksStopped&&s.inputBlocked==true&&s.submissionVerified
            Op.VERIFY_VISUAL->s.contentReady&&s.inputBlocked==true&&s.scene!=null&&s.scene==e.expected
            Op.VERIFY_FOCUS->s.contentReady&&s.inputBlocked==false&&s.scene!=null&&s.scene==e.expected&&s.focused==e.expected.owners.first()
        }
        // Unknown or unsuccessful acquire/release observation must not become success.
        val effective=if(e.op in setOf(Op.ACQUIRE,Op.RELEASE)&&!verified) Observation.UNKNOWN else obs
        val receipt=Evidence(e,s.settled,effective,verified,s.scene,s.focused,s.inputBlocked==true,s.attach,s.inputBlocked)
        core.receipt(receipt)
        if(core.outstanding()!=e){dispatched=null;cancelRequested=null}
        if(core.phase in setOf(Phase.DRAINING,Phase.RECOVERY))port.stopLocalCallbacks()
    }
}
