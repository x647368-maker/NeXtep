package io.github.x647368maker.atomicworkbench.systemui

internal object StageNativePanelIdentity {
    fun matches(title:String,className:String,type:Int):Boolean =
        type==2040 || type==2014 || title.contains("NotificationShade",true) ||
        title.contains("ControlCenter",true) || className.contains("NotificationShadeWindowView")
}
