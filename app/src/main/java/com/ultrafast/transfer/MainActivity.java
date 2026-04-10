package com.ultrafast.transfer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private boolean pendingIsSendMode = true;

    // Define all required permissions based on Android version
    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        // Storage Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES); // API 33+ requirement
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // Location is required for Wi-Fi/BT scanning on most versions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // Bluetooth permissions for API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        return permissions.toArray(new String[0]);
    }

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                
                // If standard permissions are granted, check for special MANAGE_EXTERNAL_STORAGE
                if (allGranted) {
                    checkAndRequestManageStorage();
                } else {
                    Toast.makeText(this, "Standard permissions are required.", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> manageStorageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (hasAllPermissions()) {
                    Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "All Files Access is required for file management.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        // Initial Permission Check
        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(getRequiredPermissions());
        }

        initViews();
    }

    private void checkAndRequestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    manageStorageLauncher.launch(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    manageStorageLauncher.launch(intent);
                }
            }
        }
    }

    private boolean hasAllPermissions() {
        // Check standard runtime permissions
        for (String perm : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        // Special check for Manage External Storage (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void initViews() {
        findViewById(R.id.cardSend).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            intent.putExtra("isSendMode", true);
            startActivity(intent);
        });
        findViewById(R.id.cardReceive).setOnClickListener(v -> {
            if(hasAllPermissions()) {
                Intent intent = new Intent(this, ServiceActivity.class);
                intent.putExtra("isSendMode", false);
                startActivity(intent);
            }
            else requestPermissionLauncher.launch(getRequiredPermissions());
        });
    }

    }