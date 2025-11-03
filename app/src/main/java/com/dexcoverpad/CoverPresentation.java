package com.dexcoverpad;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Presentation that is shown on the cover (non-default) display.
 * It captures touch events and forwards them via TouchCallback.
 */
public class CoverPresentation extends Presentation {
    private static final String TAG = "CoverPresentation";

    private FrameLayout root;
    private TouchCallback callback;
    private int coverWidth = 1080;
    private int coverHeight = 2520;

    public interface TouchCallback {
        void onTouchEvent(MotionEvent event, int coverWidth, int coverHeight);
    }

    public CoverPresentation(Context context, Display display, TouchCallback cb) {
        super(context, display);
        this.callback = cb;
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(getContext());
        root.setLayoutParams(new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(0x00000000); // fully transparent

        View touchView = new View(getContext());
        touchView.setLayoutParams(new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
        ));
        touchView.setOnTouchListener((v, event) -> {
            if (callback != null) {
                // deliver a copy of the event (defensive)
                callback.onTouchEvent(MotionEvent.obtain(event), coverWidth, coverHeight);
            }
            return true;
        });

        root.addView(touchView);
        setContentView(root);

        // determine display size for mapping
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            getDisplay().getMetrics(metrics);
            coverWidth = metrics.widthPixels;
            coverHeight = metrics.heightPixels;
            Log.d(TAG, "Presentation created. cover size: " + coverWidth + "x" + coverHeight);
        } catch (Exception e) {
            Log.w(TAG, "Unable to read cover display metrics: " + e.getMessage());
        }
    }

    public Point getCoverSize() {
        return new Point(coverWidth, coverHeight);
    }
}
