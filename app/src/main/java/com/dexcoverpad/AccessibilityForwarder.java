package com.dexcoverpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.MotionEvent;

/**
 * Converts cover MotionEvents into Accessibility gestures targeted at the external Dex display.
 */
public class AccessibilityForwarder {
    private static final String TAG = "AccessibilityForwarder";

    private final AccessibilityService service;
    private int dexWidth;
    private int dexHeight;

    private float startX = 0, startY = 0;
    private long startTime = 0;

    public AccessibilityForwarder(AccessibilityService svc, int dexW, int dexH) {
        this.service = svc;
        this.dexWidth = dexW;
        this.dexHeight = dexH;
    }

    public void setDexDisplaySize(int w, int h) {
        this.dexWidth = w;
        this.dexHeight = h;
    }

    public void forward(MotionEvent event, int coverW, int coverH) {
        if (service == null) {
            Log.w(TAG, "AccessibilityService not available");
            return;
        }

        float mappedX = event.getX() / (float) coverW * dexWidth;
        float mappedY = event.getY() / (float) coverH * dexHeight;

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startX = mappedX;
                startY = mappedY;
                startTime = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_MOVE:
                // Ignore continuous dispatch for performance. We'll treat moves as swipe end.
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                long dur = System.currentTimeMillis() - startTime;
                float dx = mappedX - startX;
                float dy = mappedY - startY;
                float distSq = dx*dx + dy*dy;

                if (dur < 200 && distSq < 40*40) {
                    dispatchTap(mappedX, mappedY);
                } else {
                    dispatchSwipe(startX, startY, mappedX, mappedY);
                }
                break;
        }
    }

    private void dispatchTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 100);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "Tap dispatched: " + x + "," + y);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Tap cancelled");
            }
        }, null);
    }

    private void dispatchSwipe(float sx, float sy, float ex, float ey) {
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 300);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "Swipe dispatched: " + sx + "," + sy + " -> " + ex + "," + ey);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(TAG, "Swipe cancelled");
            }
        }, null);
    }
}
