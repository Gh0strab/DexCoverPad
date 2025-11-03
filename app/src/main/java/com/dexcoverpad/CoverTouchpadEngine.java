package com.dexcoverpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;

/**
 * CoverTouchpadEngine
 *
 * - Basic 1:1 mapping (no acceleration)
 * - Approximates pointer motion by dispatching short swipe gestures repeatedly
 * - Tap -> short gesture (100ms)
 * - Long-press -> long gesture (600ms) (acts like context-click)
 * - Two-finger vertical movement -> scroll (dispatch swipe on Dex)
 *
 * Usage:
 *  - Instantiate with your AccessibilityService instance and external (Dex) width/height
 *  - Call onCoverTouch(event, coverW, coverH) from your Presentation's touch listener
 *
 */
public class CoverTouchpadEngine {
    private static final String TAG = "CoverTouchpadEngine";

    private final AccessibilityService accessibilityService;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Dex (external) display dimensions (set by caller)
    private volatile int dexWidth = 1920;
    private volatile int dexHeight = 1080;

    // Pointer tracking (in Dex coords)
    private volatile float lastDexX = -1;
    private volatile float lastDexY = -1;

    // Tap / long-press detection
    private long downTimeMs = 0;
    private float downX = 0, downY = 0;
    private static final long LONG_PRESS_MS = 600;
    private static final int TAP_MOVE_THRESHOLD_PX = 20; // small movement allowed

    // Movement dispatch throttling
    private final long MOVE_INTERVAL_MS = 50; // 50ms between small swipe dispatches
    private volatile boolean moving = false;
    private final Object moveLock = new Object();

    // Two-finger scroll detection
    private boolean twoFingerScrolling = false;
    private float lastTwoFingerAvgY = 0;

    public CoverTouchpadEngine(AccessibilityService service, int initialDexW, int initialDexH) {
        this.accessibilityService = service;
        this.dexWidth = initialDexW;
        this.dexHeight = initialDexH;
    }

    /**
     * Update the Dex external display size (call when you detect external display size)
     */
    public void setDexDisplaySize(int w, int h) {
        this.dexWidth = w;
        this.dexHeight = h;
        Log.d(TAG, "setDexDisplaySize: " + w + "x" + h);
    }

    /**
     * Main entry: called from your Presentation touch listener.
     * event is in cover pixel coordinates.
     * coverW/coverH are size of the cover display in pixels.
     */
    public void onCoverTouch(MotionEvent event, int coverW, int coverH) {
        if (accessibilityService == null) {
            Log.w(TAG, "AccessibilityService is null");
            return;
        }
        if (coverW <= 0 || coverH <= 0) {
            Log.w(TAG, "Invalid cover size");
            return;
        }

        final int action = event.getActionMasked();
        final int pointerCount = event.getPointerCount();

        // Map the primary pointer to Dex coordinates
        float localX = event.getX(0);
        float localY = event.getY(0);
        final float mappedX = (localX / (float) coverW) * dexWidth;
        final float mappedY = (localY / (float) coverH) * dexHeight;

        if (pointerCount >= 2) {
            // two-finger scroll behavior: compute average Y of first two pointers
            float y0 = event.getY(0);
            float y1 = event.getY(1);
            float avgY = (y0 + y1) / 2f;
            handleTwoFingerEvent(event, avgY, coverH);
            return;
        } else {
            // any single-finger actions should cancel two-finger scroll state
            twoFingerScrolling = false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downTimeMs = System.currentTimeMillis();
                downX = mappedX;
                downY = mappedY;

                // initialize last positions if not set
                if (lastDexX < 0 || lastDexY < 0) {
                    lastDexX = mappedX;
                    lastDexY = mappedY;
                }

                // schedule move loop start (if user moves we'll pick it up on ACTION_MOVE)
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = mappedX - lastDexX;
                float dy = mappedY - lastDexY;

                // if there's significant movement, start dispatching small swipes to move pointer
                if (Math.hypot(dx, dy) >= 1.0) {
                    // update lastDex immediately so we don't create huge leaps
                    final float targetX = mappedX;
                    final float targetY = mappedY;
                    startOrContinueMovement(targetX, targetY);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                long dur = System.currentTimeMillis() - downTimeMs;
                float movedSq = (mappedX - downX)*(mappedX - downX) + (mappedY - downY)*(mappedY - downY);

                // stop movement
                stopMovement();

                // Decide tap vs swipe end
                if (dur < 250 && movedSq < TAP_MOVE_THRESHOLD_PX * TAP_MOVE_THRESHOLD_PX) {
                    // Tap -> short gesture
                    dispatchTap(mappedX, mappedY);
                } else {
                    // A lifted drag — we can do a final small swipe to land the pointer
                    dispatchSmallSwipe(lastDexX, lastDexY, mappedX, mappedY, 40);
                }
                break;

            default:
                break;
        }
    }

    // ---------------------------
    // Two-finger scroll handling
    // ---------------------------
    private void handleTwoFingerEvent(MotionEvent event, float avgY, int coverH) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                twoFingerScrolling = true;
                lastTwoFingerAvgY = avgY;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!twoFingerScrolling) {
                    lastTwoFingerAvgY = avgY;
                    twoFingerScrolling = true;
                    return;
                }
                float deltaY = avgY - lastTwoFingerAvgY;
                lastTwoFingerAvgY = avgY;

                // map deltaY (cover space) to scroll on Dex (scale factor)
                // positive deltaY -> scroll down ; negative -> scroll up
                float scrollAmount = (deltaY / (float) coverH) * dexHeight * 1.0f; // 1:1 scale for basic mode

                // For scroll, dispatch a short swipe centered horizontally that moves opposite direction
                float centerX = dexWidth * 0.5f;
                float startY = dexHeight * 0.5f;
                float endY = startY + scrollAmount * 0.6f; // scale down to be reasonable
                dispatchSwipe(centerX, startY, centerX, endY, 120);
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                twoFingerScrolling = false;
                break;
        }
    }

    // ---------------------------
    // Movement dispatch
    // ---------------------------
    private void startOrContinueMovement(final float targetX, final float targetY) {
        synchronized (moveLock) {
            // update last desired position
            lastDexX = targetX;
            lastDexY = targetY;
            if (!moving) {
                moving = true;
                handler.post(movementRunnable);
            }
        }
    }

    private void stopMovement() {
        synchronized (moveLock) {
            moving = false;
        }
    }

    // Runnable that periodically sends a short swipe from current pointer to lastDexX,lastDexY
    private final Runnable movementRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (moveLock) {
                if (!moving) return;
                // We'll dispatch a small swipe starting from current reported pointer (we keep lastDexX/Y as target).
                // To keep things simple: do a small gesture from (lastDexX - tiny) -> (lastDexX)
                float tiny = 1.0f;
                float fromX = Math.max(0, lastDexX - tiny);
                float fromY = Math.max(0, lastDexY - tiny);
                float toX = Math.min(dexWidth - 1, lastDexX);
                float toY = Math.min(dexHeight - 1, lastDexY);
                dispatchSmallSwipe(fromX, fromY, toX, toY, 40); // 40ms short swipe to nudge pointer
                handler.postDelayed(this, MOVE_INTERVAL_MS);
            }
        }
    };

    // send a very short swipe (used for movement nudges)
    private void dispatchSmallSwipe(final float sx, final float sy, final float ex, final float ey, final long durationMs) {
        try {
            Path p = new Path();
            p.moveTo(sx, sy);
            p.lineTo(ex, ey);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, durationMs);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            accessibilityService.dispatchGesture(gesture, null, null);
            // update last pointer to end position
            lastDexX = ex;
            lastDexY = ey;
        } catch (Exception e) {
            Log.e(TAG, "dispatchSmallSwipe error: " + e.getMessage(), e);
        }
    }

    // dispatch a tap (short press)
    private void dispatchTap(final float x, final float y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 100);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            accessibilityService.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "Tap dispatched at " + x + "," + y);
                }
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w(TAG, "Tap cancelled");
                }
            }, null);
        } catch (Exception e) {
            Log.e(TAG, "dispatchTap error: " + e.getMessage(), e);
        }
    }

    // dispatch a swipe (longer gesture)
    private void dispatchSwipe(final float sx, final float sy, final float ex, final float ey, final long durationMs) {
        try {
            Path p = new Path();
            p.moveTo(sx, sy);
            p.lineTo(ex, ey);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, durationMs);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            accessibilityService.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "Swipe dispatched: " + sx + "," + sy + " -> " + ex + "," + ey);
                }
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w(TAG, "Swipe cancelled");
                }
            }, null);
            // update last pointer to end position
            lastDexX = ex;
            lastDexY = ey;
        } catch (Exception e) {
            Log.e(TAG, "dispatchSwipe error: " + e.getMessage(), e);
        }
    }

}
