package io.github.x647368maker.atomicworkbench.logging

import java.util.ArrayDeque

/** Bounded, explicitly armed evidence; elapsed time is supplied by the caller. */
internal class StageHandoffEvidenceBuffer(private val capacity:Int=24) {
    internal class Capture(val token:Long,val deadline:Long) {
        @Volatile var closed=false
        val events=ArrayDeque<String>()
        var dropped=0
        var lastMessage:String?=null
        var repeatCount=0
        val attempts=StageAttemptEvidence()
    }
    @Volatile private var current:Capture?=null

    @Synchronized fun arm(token:Long,now:Long,duration:Long):Boolean {
        if(token<0L || token>now || duration !in 1L..3_600_000L ||
            now-token>=duration || token>Long.MAX_VALUE-duration)return false
        val old=current
        if(old!=null && token<=old.token)return token==old.token && !old.closed
        current=Capture(token,token+duration)
        return true
    }

    fun hasOpenCapture():Boolean=current?.closed==false

    fun ticket(now:Long):Capture? {
        val c=current ?: return null
        if(c.closed || now<c.token)return null
        if(now>=c.deadline) {
            c.closed=true
            return null
        }
        return c
    }

    @Synchronized fun record(ticket:Capture,now:Long,nanos:Long,message:String) {
        if(current!==ticket || ticket.closed || now<ticket.token || now>=ticket.deadline)return
        // Avoid logging an identical consecutive transform on every Shell apply.
        val bounded=message.take(150)
        if(ticket.lastMessage==bounded) {
            ticket.repeatCount=(ticket.repeatCount+1).coerceAtMost(1_000_000)
            return
        }
        ticket.lastMessage=bounded
        if(ticket.events.size>=capacity.coerceAtLeast(1)) {
            ticket.events.removeFirst()
            ticket.dropped=(ticket.dropped+1).coerceAtMost(1_000_000)
        }
        ticket.events.addLast("$nanos $bounded")
    }

    @Synchronized fun beginAttempt(c:Capture,now:Long,id:Long,ns:Long,op:String) {
        if(current===c && !c.closed && now in c.token until c.deadline)c.attempts.begin(id,ns,op)
    }
    @Synchronized fun attemptStage(c:Capture,now:Long,id:Long,ns:Long,key:String,msg:String) {
        if(current===c && !c.closed && now in c.token until c.deadline)c.attempts.stage(id,ns,key,msg)
    }
    @Synchronized fun endAttempt(c:Capture,now:Long,id:Long,ns:Long,result:String) {
        if(current===c && !c.closed && now in c.token until c.deadline)c.attempts.end(id,ns,result)
    }
    @Synchronized fun bindPair(c:Capture,now:Long,id:Long,ns:Long,up:Int,down:Int) {
        if(current===c && !c.closed && now in c.token until c.deadline)c.attempts.bindPair(id,ns,up,down)
    }
    @Synchronized fun attemptWriter(c:Capture,now:Long,id:Long,role:String,task:Int,handle:Int,
                                    sample:StageAttemptEvidence.Sample,layer:()->String) {
        if(current===c && !c.closed && now in c.token until c.deadline)c.attempts.writer(id,role,task,handle,sample,layer)
    }
    @Synchronized fun finish(token:Long):String? {
        val c=current?.takeIf { it.token==token } ?: return null
        c.closed=true
        return buildString {
            appendLine("token=$token events=${c.events.size} dropped=${c.dropped} repeated=${c.repeatCount}")
            c.events.forEach { appendLine(it) }
            append(c.attempts.describe())
        }
    }
}
