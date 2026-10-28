package com.slopus.happy.probe;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Injects one vertical finger drag into whatever is on screen, as a real
 * finger would: DOWN, a MOVE every frame at an even speed, UP at the last
 * point, each stamped with the time it is delivered. `adb shell input swipe`
 * can't be trusted for flings (its moves bunch up and stall), so lists are
 * compared with this.
 *
 *     adb shell am instrument -w -e x 540 -e from 1500 -e to 1200 -e ms 100 \
 *         com.slopus.happy.probe/.FlingProbe
 *
 * `ms` is the drag's duration; 16 ms between moves. `hold` (ms, default 0)
 * keeps the finger still at the end before lifting, which should stop a fling.
 *
 * `-e stamped 1` stamps the events exactly 16 ms apart instead of with the
 * time they are delivered: on a slow emulator a delivered stamp can lag past
 * VelocityTracker's 40 ms "pointer stopped" cut-off and read as no velocity
 * at all, for stock lists too. They are still sent a frame apart, so a list
 * gets them as it would a finger's rather than as one batched move.
 */
public class FlingProbe extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    private float number(String key, float fallback) {
        String value = arguments.getString(key);
        return value == null ? fallback : Float.parseFloat(value);
    }

    @Override
    public void onStart() {
        float x = number("x", 540);
        float from = number("from", 1500);
        float to = number("to", 1200);
        long duration = (long) number("ms", 100);
        long hold = (long) number("hold", 0);
        long frame = 16;
        UiAutomation automation = getUiAutomation();
        int steps = (int) Math.max(1, duration / frame);
        if (number("stamped", 0) != 0) {
            // Delivered a frame apart, as a finger's are, but stamped exactly
            // 16 ms apart whatever the delivery lagged.
            long down = SystemClock.uptimeMillis();
            long holdFrames = hold / frame;
            inject(automation, down, down, MotionEvent.ACTION_DOWN, x, from, false);
            for (int i = 1; i <= steps + holdFrames; i++) {
                SystemClock.sleep(frame);
                float y = i <= steps ? from + (to - from) * i / steps : to;
                inject(automation, down, down + i * frame, MotionEvent.ACTION_MOVE, x, y, false);
            }
            SystemClock.sleep(frame);
            inject(automation, down, down + (steps + holdFrames + 1) * frame, MotionEvent.ACTION_UP, x, to, true);
            finish(0, new Bundle());
            return;
        }
        long down = SystemClock.uptimeMillis();
        inject(automation, down, down, MotionEvent.ACTION_DOWN, x, from);
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(frame);
            float y = from + (to - from) * i / steps;
            inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y);
        }
        if (hold > 0) {
            long end = SystemClock.uptimeMillis() + hold;
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(frame);
                inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, to);
            }
        }
        SystemClock.sleep(frame);
        inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, to);
        Bundle result = new Bundle();
        result.putString("steps", String.valueOf(steps));
        finish(0, result);
    }

    private static void inject(UiAutomation automation, long down, long time, int action, float x, float y) {
        inject(automation, down, time, action, x, y, true);
    }

    private static void inject(UiAutomation automation, long down, long time, int action, float x, float y, boolean sync) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        automation.injectInputEvent(event, sync);
        event.recycle();
    }
}
