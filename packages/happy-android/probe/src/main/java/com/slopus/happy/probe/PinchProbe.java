package com.slopus.happy.probe;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Injects one two-finger pinch and turn into whatever is on screen, which
 * `adb shell input` can't: both fingers go down `span` apart about (x, y),
 * then over `ms` spread to `span * scale` and turn by `degrees`, a MOVE
 * every frame, and lift.
 *
 *     adb shell am instrument -w -e x 540 -e y 1100 -e span 200 -e scale 1.8 \
 *         -e degrees 30 -e ms 600 com.slopus.happy.probe/.PinchProbe
 */
public class PinchProbe extends Instrumentation {
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
        float y = number("y", 1200);
        float span = number("span", 200);
        float scale = number("scale", 1.5f);
        float degrees = number("degrees", 0);
        long duration = (long) number("ms", 600);
        long frame = 16;
        int steps = (int) Math.max(1, duration / frame);
        UiAutomation automation = getUiAutomation();
        long down = SystemClock.uptimeMillis();
        float[] points = points(x, y, span, 0);
        inject(automation, down, down, MotionEvent.ACTION_DOWN, points, 1);
        SystemClock.sleep(frame);
        inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), points, 2);
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(frame);
            float t = (float) i / steps;
            points = points(x, y, span * (1 + (scale - 1) * t), degrees * t);
            inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, points, 2);
        }
        SystemClock.sleep(frame);
        inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), points, 2);
        SystemClock.sleep(frame);
        inject(automation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, points, 1);
        finish(0, new Bundle());
    }

    /** The two fingers `span` apart about (x, y), their line turned by `degrees`. */
    private static float[] points(float x, float y, float span, float degrees) {
        double angle = Math.toRadians(degrees);
        float dx = (float) (Math.cos(angle) * span / 2);
        float dy = (float) (Math.sin(angle) * span / 2);
        return new float[] {x - dx, y - dy, x + dx, y + dy};
    }

    private static void inject(UiAutomation automation, long down, long time, int action, float[] points, int count) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i * 2];
            coords[i].y = points[i * 2 + 1];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, time, action, count, properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        automation.injectInputEvent(event, true);
        event.recycle();
    }
}
