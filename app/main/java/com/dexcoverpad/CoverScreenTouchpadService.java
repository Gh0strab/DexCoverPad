package com.dexcoverpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.hardware.display.DisplayManager;

public class CoverScreenTouchpadService {

    private static final String TAG = "CoverTouchpadService";

    private Context context;
    private AccessibilityService accessibilityService;
    private Display externalDisplay;
    private int displayWidth;
    private int displayHeight;

    // Track ongoing gesture for drag events
    private Path currentPath = null;
    private GestureDescription.StrokeDescription currentStroke = null;

    public CoverScreenTouchpadService(Context context, AccessibilityService service) {
        this.context = context;
        this.accessibilityService = service;
        setupExternalDisplay();
    }

    private void setupExternalDisplay() {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            Log.w(TAG, "DisplayManager not available");
            return;
        }

        Display[] displays = dm.getDisplays();
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                externalDisplay = d;
                DisplayMetrics metrics = new DisplayMetrics();
                d.getMetrics(metrics);
                displayWidth = metrics.widthPixels;
                displayHeight = metrics.heightPixels;
                Log.d(TAG, "External display detected: " + displayWidth + "x" + displayHeight);
                break;
            }
        }

        if (externalDisplay == null) {
            Log.w(TAG, "No external display found. Touchpad will not work.");
        }
    }

    // Call this method whenever a touch happens on the cover screen
    public void onCoverScreenTouch(MotionEvent event) {
        if (externalDisplay == null || accessibilityService == null) {
            Log.w(TAG, "No external display or AccessibilityService available");
            return;
        }

        // Map cover screen coordinates to external display
        float mappedX = event.getX() / getCoverScreenWidth() * displayWidth;
        float mappedY = event.getY() / getCoverScreenHeight() * displayHeight;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startGesture(mappedX, mappedY);
                break;
            case MotionEvent.ACTION_MOVE:
                continueGesture(mappedX, mappedY);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endGesture(mappedX, mappedY);
                break;
        }
    }

    private void startGesture(float x, float y) {
        currentPath = new Path();
        currentPath.moveTo(x, y);

        currentStroke = new GestureDescription.StrokeDescription(currentPath, 0, 1, true);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(currentStroke).build();

        accessibilityService.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Gesture started");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "Gesture start cancelled");
            }
        }, null);
    }

    private void continueGesture(float x, float y) {
        if (currentPath == null) return;

        currentPath.lineTo(x, y);

        currentStroke = new GestureDescription.StrokeDescription(currentPath, 0, 1, true);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(currentStroke).build();

        accessibilityService.dispatchGesture(gesture, null, null);
    }

    private void endGesture(float x, float y) {
        if (currentPath == null) return;

        currentPath.lineTo(x, y);

        currentStroke = new GestureDescription.StrokeDescription(currentPath, 0, 1);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(currentStroke).build();

        accessibilityService.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Gesture ended");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.w(TAG, "Gesture end cancelled");
            }
        }, null);

        currentPath = null;
        currentStroke = null;
    }

    private int getCoverScreenWidth() {
        // Replace with actual cover screen width
        return 1080;
    }

    private int getCoverScreenHeight() {
        // Replace with actual cover screen height
        return 2520;
    }
}
