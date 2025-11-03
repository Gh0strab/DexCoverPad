package com.dexcoverpad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.List;

public class CoverScreenTouchpadService extends AccessibilityService {
    
    private static final String TAG = "CoverTouchpadService";
    
    private DisplayManager displayManager;
    private WindowManager windowManager;
    private WindowManager coverWindowManager;
    private Display coverDisplay;
    private ActivityManager activityManager;
    private Handler handler = new Handler();
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private DisplayManager.DisplayListener displayListener;
    
    private float lastX = 0;
    private float lastY = 0;
    private float cursorX = 0;
    private float cursorY = 0;
    
    private float sensitivity = 2.5f;
    private static final int CLICK_THRESHOLD_MS = 200;
    private static final int TAP_MOVEMENT_THRESHOLD = 20;
    
    private long touchDownTime = 0;
    private float touchDownX = 0;
    private float touchDownY = 0;
    private boolean isMoving = false;
    
    private int screenWidth = 1920;
    private int screenHeight = 1080;
    
    private float lastCursorMoveX = 0;
    private float lastCursorMoveY = 0;
    private static final int CURSOR_MOVE_DELAY_MS = 16;
    private boolean gestureInFlight = false;
    private float pendingCursorX = -1;
    private float pendingCursorY = -1;
    
    private boolean isDexMode = false;
    private boolean isAppVisible = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Cover Screen Touchpad Service created");
        
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        
        loadSensitivity();
        
        setupDisplayListener();
        startForegroundAppMonitor();
    }
    
    private void setupDisplayListener() {
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                Log.d(TAG, "Display added: " + displayId);
                checkDexMode();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                Log.d(TAG, "Display removed: " + displayId);
                checkDexMode();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                Log.d(TAG, "Display changed: " + displayId);
                checkDexMode();
                validateOverlayDisplay();
            }
        };
        
        displayManager.registerDisplayListener(displayListener, handler);
    }
    
    private void startForegroundAppMonitor() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkForegroundApp();
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }
    
    private void checkForegroundApp() {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ComponentName topActivity = tasks.get(0).topActivity;
                if (topActivity != null) {
                    String packageName = topActivity.getPackageName();
                    boolean wasVisible = isAppVisible;
                    isAppVisible = "com.dexcoverpad".equals(packageName);
                    
                    if (isAppVisible != wasVisible) {
                        Log.d(TAG, "DexCoverPad app visibility changed: " + isAppVisible);
                        updateOverlayVisibility();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking foreground app: " + e.getMessage());
        }
    }
    
    private Display getCoverDisplay() {
        Display[] displays = displayManager.getDisplays();
        Display smallestDisplay = null;
        int smallestArea = Integer.MAX_VALUE;
        
        for (Display display : displays) {
            Point size = new Point();
            display.getRealSize(size);
            int area = size.x * size.y;
            
            if (size.x <= 1200 && size.y <= 1200 && area < smallestArea) {
                smallestArea = area;
                smallestDisplay = display;
                Log.d(TAG, "Found potential cover display: " + display.getDisplayId() + " size: " + size.x + "x" + size.y);
            }
        }
        
        if (smallestDisplay != null) {
            return smallestDisplay;
        }
        
        return windowManager.getDefaultDisplay();
    }
    
    private void validateOverlayDisplay() {
        if (overlayView == null || coverDisplay == null) {
            return;
        }
        
        Point coverSize = new Point();
        coverDisplay.getRealSize(coverSize);
        
        if (coverSize.x > 1200 || coverSize.y > 1200) {
            Log.d(TAG, "Cover display is now main screen (" + coverSize.x + "x" + coverSize.y + ") - removing overlay");
            removeOverlay();
        }
    }
    
    private void checkDexMode() {
        boolean wasDexMode = isDexMode;
        isDexMode = isDexActive();
        
        Log.d(TAG, "DeX mode check: " + isDexMode);
        
        if (isDexMode && !wasDexMode) {
            Log.d(TAG, "DeX mode activated");
            updateScreenDimensions();
            cursorX = screenWidth / 2;
            cursorY = screenHeight / 2;
            updateOverlayVisibility();
        } else if (!isDexMode && wasDexMode) {
            Log.d(TAG, "DeX mode deactivated");
            removeOverlay();
        } else if (isDexMode) {
            updateScreenDimensions();
        }
    }
    
    private void updateOverlayVisibility() {
        boolean shouldShowOverlay = isDexMode && isAppVisible;
        
        Log.d(TAG, "Update overlay - DeX: " + isDexMode + ", App visible: " + isAppVisible + ", Touchpad: " + shouldShowOverlay);
        
        if (shouldShowOverlay && overlayView == null) {
            createOverlay();
        } else if (!shouldShowOverlay && overlayView != null) {
            removeOverlay();
        }
    }
    
    private void loadSensitivity() {
        android.content.SharedPreferences prefs = getSharedPreferences("DexCoverPadPrefs", MODE_PRIVATE);
        sensitivity = prefs.getFloat("touchpad_sensitivity", 2.5f);
        Log.d(TAG, "Loaded sensitivity: " + sensitivity);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service connected - checking for DeX mode");
        checkDexMode();
    }
    
    private void createOverlay() {
        if (overlayView != null) {
            Log.d(TAG, "Overlay already exists");
            return;
        }
        
        if (!isDexActive()) {
            Log.d(TAG, "Not creating overlay - DeX is not active");
            return;
        }
        
        if (!isAppVisible) {
            Log.d(TAG, "Not creating overlay - DexCoverPad app is not visible");
            return;
        }
        
        coverDisplay = getCoverDisplay();
        if (coverDisplay == null) {
            Log.e(TAG, "Could not find cover display");
            return;
        }
        
        Point coverSize = new Point();
        coverDisplay.getRealSize(coverSize);
        
        if (coverSize.x > 1200 || coverSize.y > 1200) {
            Log.d(TAG, "Not creating overlay - display too large (size: " + coverSize.x + "x" + coverSize.y + ")");
            return;
        }
        
        android.content.Context displayContext = createDisplayContext(coverDisplay);
        coverWindowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
        
        overlayView = new FrameLayout(displayContext);
        overlayView.setBackgroundColor(0x20FF0000);
        
        overlayParams = new WindowManager.LayoutParams(
            coverSize.x,
            coverSize.y,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouchEvent(event);
            }
        });
        
        try {
            coverWindowManager.addView(overlayView, overlayParams);
            Log.d(TAG, "✓ Touchpad overlay created on cover screen - DexCoverPad app is visible");
            Log.d(TAG, "✓ Cover screen touchpad active! (Display " + coverDisplay.getDisplayId() + ", " + coverSize.x + "x" + coverSize.y + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create overlay: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void removeOverlay() {
        if (overlayView != null && overlayView.getParent() != null) {
            try {
                if (coverWindowManager != null) {
                    coverWindowManager.removeView(overlayView);
                } else {
                    windowManager.removeView(overlayView);
                }
                Log.d(TAG, "Overlay view removed - DexCoverPad app not visible");
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay: " + e.getMessage());
            }
            overlayView = null;
            coverWindowManager = null;
            coverDisplay = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                String packageName = event.getPackageName().toString();
                boolean wasVisible = isAppVisible;
                isAppVisible = "com.dexcoverpad".equals(packageName);
                
                if (isAppVisible != wasVisible) {
                    Log.d(TAG, "App visibility changed via accessibility event: " + isAppVisible);
                    updateOverlayVisibility();
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }
    
    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Log.d(TAG, "Service unbound");
        removeOverlay();
        if (displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
        if (displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Service destroyed");
    }
    
    private void updateScreenDimensions() {
        Display[] displays = displayManager.getDisplays();
        for (Display display : displays) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                Point size = new Point();
                display.getRealSize(size);
                screenWidth = size.x;
                screenHeight = size.y;
                Log.d(TAG, "External display size: " + screenWidth + "x" + screenHeight);
                return;
            }
        }
        
        Display defaultDisplay = windowManager.getDefaultDisplay();
        Point size = new Point();
        defaultDisplay.getRealSize(size);
        screenWidth = size.x;
        screenHeight = size.y;
        Log.d(TAG, "Using default display size: " + screenWidth + "x" + screenHeight);
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        checkDexMode();
        validateOverlayDisplay();
    }
    
    private boolean isDexActive() {
        Display[] displays = displayManager.getDisplays();
        return displays.length > 1;
    }
    
    private boolean handleTouchEvent(MotionEvent event) {
        if (!isDexActive()) {
            Log.d(TAG, "Touch ignored - DeX not active");
            return false;
        }
        
        if (!isAppVisible) {
            Log.d(TAG, "Touch ignored - DexCoverPad app not visible");
            return false;
        }
        
        loadSensitivity();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                isMoving = false;
                return true;
                
            case MotionEvent.ACTION_MOVE:
                float deltaX = (event.getX() - lastX) * sensitivity;
                float deltaY = (event.getY() - lastY) * sensitivity;
                
                float totalMovement = Math.abs(event.getX() - touchDownX) + 
                                    Math.abs(event.getY() - touchDownY);
                if (totalMovement > TAP_MOVEMENT_THRESHOLD) {
                    isMoving = true;
                }
                
                cursorX += deltaX;
                cursorY += deltaY;
                
                cursorX = Math.max(0, Math.min(screenWidth, cursorX));
                cursorY = Math.max(0, Math.min(screenHeight, cursorY));
                
                moveCursor(cursorX, cursorY);
                
                lastX = event.getX();
                lastY = event.getY();
                return true;
                
            case MotionEvent.ACTION_UP:
                long touchDuration = System.currentTimeMillis() - touchDownTime;
                
                if (!isMoving && touchDuration < CLICK_THRESHOLD_MS) {
                    performClick(cursorX, cursorY);
                    Log.d(TAG, "Click performed at: " + cursorX + ", " + cursorY);
                }
                return true;
        }
        
        return false;
    }
    
    private void moveCursor(float x, float y) {
        if (Math.abs(x - lastCursorMoveX) < 1 && Math.abs(y - lastCursorMoveY) < 1) {
            return;
        }
        
        if (gestureInFlight) {
            pendingCursorX = x;
            pendingCursorY = y;
            return;
        }
        
        dispatchCursorGesture(x, y);
    }
    
    private void dispatchCursorGesture(float x, float y) {
        lastCursorMoveX = x;
        lastCursorMoveY = y;
        
        Path movePath = new Path();
        movePath.moveTo(x, y);
        movePath.lineTo(x + 0.1f, y + 0.1f);
        
        GestureDescription.StrokeDescription stroke = 
            new GestureDescription.StrokeDescription(movePath, 0, CURSOR_MOVE_DELAY_MS);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        
        gestureInFlight = true;
        dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                gestureInFlight = false;
                
                if (pendingCursorX >= 0 && pendingCursorY >= 0) {
                    float px = pendingCursorX;
                    float py = pendingCursorY;
                    pendingCursorX = -1;
                    pendingCursorY = -1;
                    dispatchCursorGesture(px, py);
                }
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                gestureInFlight = false;
                pendingCursorX = -1;
                pendingCursorY = -1;
            }
        }, null);
    }
    
    private void performClick(float x, float y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        clickPath.lineTo(x + 1.0f, y + 1.0f);
        
        GestureDescription.StrokeDescription stroke = 
            new GestureDescription.StrokeDescription(clickPath, 0, 50);
        
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        
        boolean dispatched = dispatchGesture(
            builder.build(),
            new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    Log.d(TAG, "Click gesture completed");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    Log.d(TAG, "Click gesture cancelled");
                }
            },
            null
        );
        
        Log.d(TAG, "Click dispatched: " + dispatched);
    }
}
