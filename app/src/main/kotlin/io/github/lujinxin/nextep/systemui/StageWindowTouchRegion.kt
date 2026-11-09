package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Rect
import android.graphics.Region
import android.view.View

internal object StageWindowTouchRegion {
    fun add(region: Region, child: View) {
        val rect=Rect()
        if(child.visibility==View.VISIBLE && child.isShown && child.getGlobalVisibleRect(rect)) region.op(rect,Region.Op.UNION)
    }
}
