package workbench2.nativebridge

import java.lang.reflect.Modifier

/** Read only. Never calls OEM services, instantiates views or changes task ownership.
 * A resolved member is NOT permission, compatibility, or task-return evidence. */
data class Names(val view:String="com.oplus.flexiblewindow.FlexibleTaskView",
                 val manager:String="com.oplus.flexiblewindow.FlexibleWindowManager",
                 val surfaceView:String="android.view.SurfaceView",
                 val context:String="android.content.Context",
                 val bundle:String="android.os.Bundle",
                 val rect:String="android.graphics.Rect",
                 val intent:String="android.content.Intent",
                 val surface:String="android.view.SurfaceControl",
                 val taskInfo:String="android.app.ActivityManager\$RunningTaskInfo")
data class ProbeResult(val firmware:String,val hostVersion:String,val missing:List<String>,val present:List<String>){
    val symbolsResolved get()=firmware.isNotBlank()&&hostVersion.isNotBlank()&&missing.isEmpty()
    // Intentionally no `authorized` or `embeddingReady` field.
}
class NativeSymbolProbe(private val loader:ClassLoader,private val names:Names=Names()){
    fun inspect(firmware:String,hostVersion:String):ProbeResult{
        val missing=mutableListOf<String>();val present=mutableListOf<String>()
        fun checkMember(label:String,block:()->Unit){try{block();present+=label}catch(e:ReflectiveOperationException){missing+="$label: ${e.javaClass.simpleName}"}catch(e:LinkageError){missing+="$label: ${e.javaClass.simpleName}"}catch(e:SecurityException){missing+="$label: SecurityException"}catch(e:IllegalArgumentException){missing+="$label: invalid type"}}
        fun cls(n:String)=Class.forName(n,false,loader)
        fun method(c:String,n:String,result:Class<*>,isStatic:Boolean,vararg params:Class<*>){val m=cls(c).getMethod(n,*params);require(m.returnType==result&&Modifier.isStatic(m.modifiers)==isStatic)}
        val void=Void.TYPE;val bool=Boolean::class.javaPrimitiveType!!;val int=Int::class.javaPrimitiveType!!
        val v=names.view;val m=names.manager
        checkMember("host fingerprint/version"){require(firmware.isNotBlank()&&hostVersion.isNotBlank())}
        checkMember("view type"){require(cls(names.surfaceView).isAssignableFrom(cls(v)))}
        checkMember("listener type"){require(cls("$v\$Listener").isInterface)}
        checkMember("view constructor"){cls(v).getConstructor(cls(names.context))}
        checkMember("init"){method(v,"init",void,false,cls(names.bundle))}
        checkMember("listener registration"){method(v,"setListener",void,false,java.util.concurrent.Executor::class.java,cls("$v\$Listener"))}
        checkMember("resize"){method(v,"resize",void,false,cls(names.rect))}
        checkMember("release"){method(v,"release",void,false)}
        checkMember("getInstance"){method(m,"getInstance",cls(m),true)}
        checkMember("app support"){method(m,"isAppSupportPocketStudio",bool,false,cls(names.intent),int)}
        checkMember("focus"){method(m,"setFocusAppForEmbeddedTask",void,false,int)}
        checkMember("detach"){method(m,"removeEmbeddedContainerTask",void,false,int,int)}
        // Private fields are diagnostic only: presence doesn't warrant making them accessible.
        checkMember("leash field"){val f=cls(v).getDeclaredField("mTaskLeash");require(f.type==cls(names.surface)&&!Modifier.isStatic(f.modifiers))}
        checkMember("task identity field"){val f=cls(v).getDeclaredField("mTaskInfo");require(f.type==cls(names.taskInfo)&&!Modifier.isStatic(f.modifiers))}
        return ProbeResult(firmware,hostVersion,missing.toList(),present.toList())
    }
    /** Candidate metadata supplement, not an execution prerequisite or an authorization grant.
     * No field value, accessibility change, invocation, instance construction or hook. */
    fun inspectLifecycleCandidates(): List<SymbolObservation> {
        val out=mutableListOf<SymbolObservation>()
        fun cls(n:String)=Class.forName(n,false,loader)
        fun observe(group:String,label:String,resolve:()->String){
            try{out+=SymbolObservation(group,label,"RESOLVED",resolve())}
            catch(e:ReflectiveOperationException){out+=SymbolObservation(group,label,"UNRESOLVED",e.javaClass.simpleName)}
            catch(e:LinkageError){out+=SymbolObservation(group,label,"UNRESOLVED",e.javaClass.simpleName)}
            catch(e:SecurityException){out+=SymbolObservation(group,label,"UNRESOLVED",e.javaClass.simpleName)}
            catch(e:IllegalArgumentException){out+=SymbolObservation(group,label,"UNRESOLVED",e.javaClass.simpleName)}
        }
        val v=names.view
        val bool=Boolean::class.javaPrimitiveType!!
        val int=Int::class.javaPrimitiveType!!
        val float=Float::class.javaPrimitiveType!!
        fun field(group:String,name:String,type:()->Class<*>)=observe(group,name){
            val f=cls(v).getDeclaredField(name)
            require(f.type==type()&&!Modifier.isStatic(f.modifiers))
            f.type.name
        }
        fun method(group:String,name:String,params:()->Array<Class<*>> = {emptyArray()})=observe(group,name){
            val c=cls(v); val p=params()
            val m=try{c.getDeclaredMethod(name,*p)}catch(e:NoSuchMethodException){c.getMethod(name,*p)}
            require(m.returnType==Void.TYPE&&!Modifier.isStatic(m.modifiers))
            m.toGenericString()
        }
        field("input","mInterceptInputEvent"){bool}
        field("presentation","mNeedRotateTaskLeash"){bool}
        field("presentation","mCornerRadius"){float}
        field("presentation","mTransaction"){cls(names.surface+"$"+"Transaction")}
        field("presentation","mDrawState"){int}
        field("presentation","mReparentAlign"){int}
        field("release","mSuperLocked"){bool}
        // Token type is intentionally recorded rather than assumed from competitor source.
        observe("identity","mTaskToken"){
            val f=cls(v).getDeclaredField("mTaskToken");require(!Modifier.isStatic(f.modifiers));f.type.name
        }
        method("launch","setEnforceStart"){arrayOf(bool)}
        method("launch","setNoNeedStartEmbedded"){arrayOf(bool)}
        method("release","releaseExtraView")
        method("presentation","reparent")
        method("input","interceptBackPressedOnTaskRoot"){arrayOf(bool)}
        method("identity","onTaskAppeared"){arrayOf(cls(names.taskInfo),cls(names.surface))}
        method("identity","onTaskInfoChanged"){arrayOf(cls(names.taskInfo),cls(names.surface))}
        method("release","onTaskVanished"){arrayOf(cls(names.taskInfo))}
        method("release","updateTaskVisibilityWhenSurfaceDestroyed")
        observe("host","candidate ContainerActivity"){
            val c=cls("com.oplus.pscanvas.canvasmode.canvas.ContainerActivity")
            require(cls("android.app.Activity").isAssignableFrom(c));c.name
        }
        observe("identity","candidate ATM.getTasks"){
            val atm=cls("android.app.ActivityTaskManager");val service=cls("android.app.IActivityTaskManager")
            val get=atm.getMethod("getService");require(Modifier.isStatic(get.modifiers)&&get.returnType==service)
            val tasks=service.getMethod("getTasks",int,bool,bool,int)
            require(!Modifier.isStatic(tasks.modifiers)&&java.util.List::class.java.isAssignableFrom(tasks.returnType));tasks.toGenericString()
        }
        return out.toList()
    }

}

/** UNRESOLVED can mean absent, signature mismatch, or reflection visibility restriction. */
data class SymbolObservation(val group:String,val label:String,val status:String,val detail:String)
