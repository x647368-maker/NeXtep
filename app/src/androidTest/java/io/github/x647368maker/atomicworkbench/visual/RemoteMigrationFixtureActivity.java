package io.github.x647368maker.atomicworkbench.visual;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.View;

/** Standalone test APK activity: only Android framework classes, no Kotlin runtime. */
public final class RemoteMigrationFixtureActivity extends Activity {
    public static final int initialMarker = Color.rgb(231, 33, 167);
    public static final int mainMarker = Color.rgb(29, 205, 106);
    public static final int returnMarker = Color.rgb(41, 92, 239);
    private boolean visitedMain;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        visitedMain = state != null && state.getBoolean("visitedMain");
        setContentView(new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Display target = getDisplay();
                if (target == null) return;
                int color;
                if (target.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    visitedMain = true;
                    color = mainMarker;
                } else {
                    color = visitedMain ? returnMarker : initialMarker;
                }
                canvas.drawColor(color);
                if (isShown()) postInvalidateDelayed(50L);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("visitedMain", visitedMain);
        super.onSaveInstanceState(state);
    }
}
