package com.slopus.happy.probe;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Dev-only, one real pointer: hold/drag/release, including system back's edge swipe. */
public class HoldDragProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; start(); }
    private int value(String key, int fallback) {
        String text = arguments.getString(key);
        return text == null ? fallback : Integer.parseInt(text);
    }
    @Override public void onStart() {
        UiAutomation automation = getUiAutomation();
        int x = value("x", 540), from = value("from", 1300), to = value("to", 1500);
        int toX = value("toX", x);
        int hold = Math.max(0, Math.min(10000, value("holdMs", 900)));
        int duration = Math.max(16, Math.min(10000, value("moveMs", 700)));
        int endHold = Math.max(0, Math.min(10000, value("endHoldMs", 0)));
        long down = SystemClock.uptimeMillis();
        inject(automation, down, MotionEvent.ACTION_DOWN, x, from);
        SystemClock.sleep(hold);
        int steps = Math.max(1, duration / 16);
        for (int step = 1; step <= steps; step++) {
            SystemClock.sleep(16);
            inject(automation, down, MotionEvent.ACTION_MOVE, x + (toX - x) * step / (float) steps, from + (to - from) * step / (float) steps);
        }
        SystemClock.sleep(endHold);
        inject(automation, down, value("cancel", 0) == 0 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL, toX, to);
        Bundle result = new Bundle();
        result.putString("gesture", value("cancel", 0) == 0 ? "held-drag-release" : "held-drag-cancel");
        finish(0, result);
    }
    private void inject(UiAutomation automation, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Gesture injection refused");
        } finally { event.recycle(); }
    }
}