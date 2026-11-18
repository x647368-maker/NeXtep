package io.github.x647368maker.atomicworkbench.logging

import java.util.ArrayDeque
import kotlin.math.abs

/** Bounded opt-in evidence. Caller serializes access; no surface handles retained. */
internal class StageAttemptEvidence {
    data class Sample(val ns:Long,val sx:Float,val sy:Float,val x:Float,val y:Float,
                      val l:Int,val t:Int,val r:Int,val b:Int) {
        fun sameCrop(other:Sample)=l==other.l && t==other.t && r==other.r && b==other.b
        fun line()="$ns s=$sx,$sy xy=$x,$y c=$l,$t,$r,$b"
    }
    private class Track(val role:String,val task:Int,val handle:Int,val layer:String,val first:Sample) {
        var last=first
        var before:Sample?=null
        var after:Sample?=null
        var cropBefore:Sample?=null
        var cropAfter:Sample?=null
        var maxStep=0f
        var count=1
        fun add(s:Sample) {
            count=(count+1).coerceAtMost(1_000_000)
            val step=maxOf(abs(s.sx-last.sx),abs(s.sy-last.sy))
            if(step>maxStep) { maxStep=step; before=last; after=s }
            if(!s.sameCrop(last)) { cropBefore=last; cropAfter=s }
            last=s
        }
        fun render():String=buildString {
            appendLine("$role task=$task layer=$layer handle=$handle count=$count max_scale_step=$maxStep")
            appendLine(" first "+first.line())
            before?.let { appendLine(" jump_before "+it.line()) }
            after?.let { appendLine(" jump_after "+it.line()) }
            cropBefore?.let { appendLine(" crop_before "+it.line()) }
            cropAfter?.let { appendLine(" crop_after "+it.line()) }
            appendLine(" last "+last.line())
        }
    }
    private class Attempt(val id:Long,val start:Long,val operation:String) {
        val stages=linkedMapOf<String,String>()
        val tracks=mutableListOf<Track>()
        var stageOverflow=0
        var trackOverflow=0
        var up:Int?=null
        var down:Int?=null
        var result="ongoing"
        var end:Long?=null
        fun render():String=buildString {
            appendLine("attempt=$id start_ns=$start end_ns=$end op=$operation result=$result stage_overflow=$stageOverflow track_overflow=$trackOverflow")
            stages.values.forEach { appendLine(it) }
            tracks.forEach { append(it.render()) }
        }
    }
    private val history=ArrayDeque<Attempt>()
    private var active:Attempt?=null
    private var evicted=0
    fun begin(id:Long,ns:Long,operation:String) {
        active?.let { end(it.id,ns,"superseded") }
        val a=Attempt(id,ns,operation.take(24))
        history.addLast(a)
        while(history.size>3) { history.removeFirst();evicted++ }
        active=a
    }
    fun end(id:Long,ns:Long,result:String) {
        val a=active?.takeIf { it.id==id } ?: return
        a.end=ns;a.result=result.take(80);active=null
    }
    fun stage(id:Long,ns:Long,key:String,message:String) {
        val a=active?.takeIf { it.id==id } ?: return
        if(key in a.stages)return
        if(a.stages.size>=32) { a.stageOverflow++;return }
        a.stages[key]="$ns "+message.take(175)
    }
    fun bindPair(id:Long,ns:Long,up:Int,down:Int) {
        val a=active?.takeIf { it.id==id } ?: return
        if(up<0 || down<0 || up==down)return
        if(a.up!=null)return
        a.up=up;a.down=down
        stage(id,ns,"task_pair","task_pair up=$up down=$down")
    }
    fun writer(id:Long,role:String,task:Int,handle:Int,s:Sample,layer:()->String) {
        val a=active?.takeIf { it.id==id } ?: return
        // Actual task writes are temporal observations; they carry no per-tap
        // callback token. Refuse attribution to unrelated tasks.
        if(a.up==null || (task!=a.up && task!=a.down))return
        val old=a.tracks.firstOrNull { it.role==role && it.task==task && it.handle==handle }
        if(old!=null) { old.add(s);return }
        if(a.tracks.size>=4) { a.trackOverflow++;return }
        a.tracks.add(Track(role,task,handle,layer().take(24),s))
    }
    fun describe():String=buildString {
        appendLine("attempt_history=${history.size} evicted=$evicted; writer samples are transaction intents, not compositor pixels")
        history.forEach { a ->
            val value=a.render()
            if(value.length<=10_000)append(value)
            else appendLine("attempt=${a.id} export_overflow=true start_ns=${a.start} end_ns=${a.end}")
        }
    }
}
