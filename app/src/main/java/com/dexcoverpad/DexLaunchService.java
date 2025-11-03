package com.dexcoverpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import androidx.core.app.NotificationCompat;

public class DexLaunchService extends Service {
    
    private static final String TAG = "DexLaunchService";
    private static final String CHANNEL_ID = "dex_monitor_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private DisplayManager displayManager;
    private Handler handler = new Handler();
    private BroadcastReceiver hdmiReceiver;
    private boolean isMonitoring = false;
    private CoverPresentation coverPresentation = null;
    private AccessibilityForwarder forwarder = null;
    private AccessibilityService accessibilityServiceInstance = null; // supply your running AccessibilityService here
    private boolean presentationShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        createNotificationChannel();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        
        registerHdmiReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        
        Notification notification = createNotification("Monitoring HDMI connection...");
        startForeground(NOTIFICATION_ID, notification);
        
        if (intent != null && intent.getBooleanExtra("hdmi_connected", false)) {
            Log.d(TAG, "HDMI connected - attempting to launch DeX");
            launchDeX();
        }
        
        if (!isMonitoring) {
            startMonitoring();
        }
        
        return START_STICKY;
    }
    
    private void registerHdmiReceiver() {
        hdmiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("android.hardware.display.action.HDMI_PLUGGED".equals(action) ||
                    "android.intent.action.HDMI_AUDIO_PLUG".equals(action)) {
                    
                    boolean plugged = intent.getBooleanExtra("state", false);
                    Log.d(TAG, "HDMI state changed: " + plugged);
                    
                    if (plugged) {
                        launchDeX();
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.hardware.display.action.HDMI_PLUGGED");
        filter.addAction("android.intent.action.HDMI_AUDIO_PLUG");
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hdmiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(hdmiReceiver, filter);
        }
    }
    
    private void startMonitoring() {
        isMonitoring = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkDisplays();
                if (isMonitoring) {
                    handler.postDelayed(this, 5000);
                }
            }
        }, 5000);
    }
    
    private void checkDisplays() {
        Display[] displays = displayManager.getDisplays();
        boolean externalDisplayFound = false;
        
        for (Display display : displays) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                externalDisplayFound = true;
                Log.d(TAG, "External display detected: " + display.getName());
                break;
            }
        }
        
        if (externalDisplayFound && !isDexActive()) {
            Log.d(TAG, "External display found but DeX not active, attempting launch");
            launchDeX();
        }
    }
    
    private void launchDeX() {
        try {
            Intent dexIntent = new Intent();
            dexIntent.setClassName("com.sec.android.desktopmode.uiservice",
                                  "com.sec.android.desktopmode.uiservice.DesktopModeService");
            dexIntent.putExtra("start_dex", true);
            
            try {
                startService(dexIntent);
                Log.d(TAG, "DeX launch intent sent (Method 1)");
            } catch (Exception e) {
                Log.d(TAG, "Method 1 failed, trying alternative methods");
                launchDexAlternative();
            }
            
            updateNotification("DeX activated - Cover screen touchpad ready");
            
        } catch (Exception e) {
            Log.e(TAG, "Error launching DeX: " + e.getMessage());
            updateNotification("Monitoring HDMI (DeX auto-launch attempted)");
        }
    }
    
    private void launchDexAlternative() {
        try {
            Intent intent = new Intent("com.samsung.android.app.dexonpc.action.LAUNCH");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.d(TAG, "DeX launch intent sent (Method 2)");
        } catch (Exception e) {
            Log.d(TAG, "Alternative DeX launch failed: " + e.getMessage());
            
            try {
                Intent settingsIntent = new Intent("com.samsung.settings.LABS_SETTINGS");
                settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settingsIntent);
                Log.d(TAG, "Opened Labs settings for manual DeX activation");
            } catch (Exception ex) {
                Log.e(TAG, "All DeX launch methods failed");
            }
        }
    }
    
    private boolean isDexActive() {
        try {
            Display[] displays = displayManager.getDisplays();
            for (Display display : displays) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking DeX status: " + e.getMessage());
        }
        return false;
    }
    
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "DeX Monitor",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Monitors HDMI connection and launches DeX");
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
    
    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeX CoverPad")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String text) {
        Notification notification = createNotification(text);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isMonitoring = false;
        if (hdmiReceiver != null) {
            unregisterReceiver(hdmiReceiver);
        }
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Service destroyed");
    }
    @Override
    private void showCoverPresentationIfNeeded() {
        if (presentationShown) return;
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return;
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display cover = null;
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                cover = d;
                break;
            }
        }
        if (cover == null) {
            Log.d(TAG, "No cover display found");
            return;
        }
    
        int dexW = 1920; // obtain actual external display resolution programmatically if possible
        int dexH = 1080;
        
        CoverTouchpadEngine engine = new CoverTouchpadEngine(accessibilityServiceInstance, dexW, dexH);
        
        coverPresentation = new CoverPresentation(this, coverDisplay, (event, cw, ch) -> {
            // forward to engine
            engine.onCoverTouch(event, cw, ch);
        });
    
        try {
            coverPresentation.show();
            presentationShown = true;
            Log.d(TAG, "CoverPresentation shown");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show CoverPresentation: " + e.getMessage());
        }
    
        // create forwarder with current accessibility instance (you must have it)
        if (accessibilityServiceInstance != null) {
            forwarder = new AccessibilityForwarder(accessibilityServiceInstance, dexW, dexH);
        } else {
            Log.w(TAG, "No AccessibilityService instance provided; gestures will not dispatch");
        }
    }
    @Override
    private void hideCoverPresentation() {
        try {
            if (coverPresentation != null) {
                coverPresentation.dismiss();
                coverPresentation = null;
                presentationShown = false;
                Log.d(TAG, "CoverPresentation dismissed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing presentation: " + e.getMessage());
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
