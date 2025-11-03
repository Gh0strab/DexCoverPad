package com.dexcoverpad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class HdmiDetectionReceiver extends BroadcastReceiver {
    
    private static final String TAG = "HdmiDetectionReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received broadcast: " + action);
        
        if (!MainActivity.isAutoDexEnabled(context)) {
            Log.d(TAG, "Auto-DeX is disabled, ignoring HDMI event");
            return;
        }
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.d(TAG, "Boot completed - starting monitoring service");
            Intent serviceIntent = new Intent(context, DexLaunchService.class);
            context.startForegroundService(serviceIntent);
        }
        else if ("android.hardware.display.action.HDMI_PLUGGED".equals(action) ||
                 "android.intent.action.HDMI_AUDIO_PLUG".equals(action)) {
            
            boolean plugged = intent.getBooleanExtra("state", false);
            Log.d(TAG, "HDMI plugged state: " + plugged);
            
            if (plugged) {
                Intent serviceIntent = new Intent(context, DexLaunchService.class);
                serviceIntent.putExtra("hdmi_connected", true);
                context.startForegroundService(serviceIntent);
            } else {
                Log.d(TAG, "HDMI disconnected");
            }
        }
    }
}
