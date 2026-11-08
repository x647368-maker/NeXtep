package io.github.x647368maker.atomicworkbench.display

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.Matrix

/** Native events go only to this slot's current non-default display. No accessibility simulation. */
internal class SlotInputBridge(context: Context) {
    private val manager = context.getSystemService(InputManager::class.java)
    private val inject by lazy { InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }
    private val target by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }
    fun send(event: MotionEvent, displayId: Int, mapping: SlotTouchProjection, xOffset: Int, yOffset: Int): Result<Unit> = runCatching {
        check(displayId > 0) { "小窗显示已关闭" }
        val copy = MotionEvent.obtain(event)
        try {
            copy.offsetLocation(-xOffset.toFloat() - mapping.left, -yOffset.toFloat() - mapping.top)
            copy.transform(Matrix().apply { setScale(1f / mapping.scale, 1f / mapping.scale) })
            copy.source = InputDevice.SOURCE_TOUCHSCREEN
            target.invoke(copy, displayId)
            check(inject.invoke(manager, copy, 0) == true) { "系统未接受小窗触摸" }
        } finally { copy.recycle() }
    }
}
