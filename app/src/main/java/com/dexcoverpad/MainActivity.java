package com.dexcoverpad;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {
    
    private static final String PREFS_NAME = "DexCoverPadPrefs";
    private static final String PREF_AUTO_DEX = "auto_dex_enabled";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    private SwitchCompat switchAutoDex;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        requestNotificationPermissionIfNeeded();
        
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        switchAutoDex = findViewById(R.id.switch_auto_dex);
        statusText = findViewById(R.id.status);
        MaterialButton btnAccessibility = findViewById(R.id.btn_accessibility);
        ImageButton btnSettings = findViewById(R.id.btn_settings);
        
        switchAutoDex.setChecked(prefs.getBoolean(PREF_AUTO_DEX, true));
        
        switchAutoDex.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_AUTO_DEX, isChecked).apply();
            updateStatus();
            
            if (isChecked) {
                if (hasNotificationPermission()) {
                    startDexService();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, "Please grant notification permission first", Toast.LENGTH_LONG).show();
                    requestNotificationPermissionIfNeeded();
                } else {
                    startDexService();
                }
            } else {
                Intent serviceIntent = new Intent(this, DexLaunchService.class);
                stopService(serviceIntent);
            }
        });
        
        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        
        btnSettings.setOnClickListener(v -> showSensitivitySettings());
        
        updateStatus();
    }
    
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    
    private void startDexService() {
        Intent serviceIntent = new Intent(this, DexLaunchService.class);
        startForegroundService(serviceIntent);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        
        if (switchAutoDex.isChecked() && hasNotificationPermission()) {
            new android.os.Handler().postDelayed(() -> startDexService(), 500);
        }
    }
    
    private void updateStatus() {
        boolean autoDexEnabled = switchAutoDex.isChecked();
        boolean touchpadEnabled = isAccessibilityServiceEnabled();
        
        StringBuilder status = new StringBuilder("Status: ");
        if (autoDexEnabled && touchpadEnabled) {
            status.append("Fully Active ✓");
        } else if (autoDexEnabled) {
            status.append("Auto-DeX enabled, enable touchpad in Accessibility");
        } else if (touchpadEnabled) {
            status.append("Touchpad enabled, enable Auto-DeX");
        } else {
            status.append("Ready - Enable features above");
        }
        
        statusText.setText(status.toString());
    }
    
    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        
        if (enabledServices == null) {
            return false;
        }
        
        String ourService = getPackageName() + "/" + 
                          CoverScreenTouchpadService.class.getName();
        return enabledServices.contains(ourService);
    }
    
    public static boolean isAutoDexEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_DEX, true);
    }
    
    private void showSensitivitySettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Touchpad Sensitivity");
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_sensitivity, null);
        SeekBar sensitivitySeekBar = dialogView.findViewById(R.id.sensitivity_seekbar);
        TextView sensitivityValue = dialogView.findViewById(R.id.sensitivity_value);
        
        float currentSensitivity = prefs.getFloat("touchpad_sensitivity", 2.5f);
        int progress = (int)((currentSensitivity - 0.5f) * 20);
        sensitivitySeekBar.setProgress(progress);
        sensitivityValue.setText(String.format("%.1f", currentSensitivity));
        
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sensitivity = 0.5f + (progress / 20.0f);
                sensitivityValue.setText(String.format("%.1f", sensitivity));
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        builder.setView(dialogView);
        builder.setPositiveButton("Save", (dialog, which) -> {
            float sensitivity = 0.5f + (sensitivitySeekBar.getProgress() / 20.0f);
            prefs.edit().putFloat("touchpad_sensitivity", sensitivity).apply();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
                if (switchAutoDex.isChecked()) {
                    startDexService();
                }
            } else {
                Toast.makeText(this, "Notification permission needed for foreground service", Toast.LENGTH_LONG).show();
                switchAutoDex.setChecked(false);
            }
        }
    }
}
