package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.View

/** Test APK only. Draw distinct actual pixels for initial, promoted and returned states. */
class RemoteMigrationFixtureActivity : Activity() {
    private var visitedMain=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visitedMain=savedInstanceState?.getBoolean("visitedMain") ?: false
        setContentView(object:View(this) {
            override fun onDraw(canvas:Canvas) {
                super.onDraw(canvas)
                val target=display?.displayId ?: return
                val color=if(target==Display.DEFAULT_DISPLAY) {
                    visitedMain=true
                    mainMarker
                } else if(visitedMain) returnMarker else initialMarker
                canvas.drawColor(color)
                // Smoke fixture only, not a frame-rate/timing experiment.
                if(isShown)postInvalidateDelayed(50L)
            }
        })
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("visitedMain",visitedMain)
        super.onSaveInstanceState(outState)
    }

    companion object {
        internal val initialMarker=Color.rgb(231,33,167)
        internal val mainMarker=Color.rgb(29,205,106)
        internal val returnMarker=Color.rgb(41,92,239)
    }
}
