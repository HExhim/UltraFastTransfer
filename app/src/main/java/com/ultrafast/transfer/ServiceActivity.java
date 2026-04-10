package com.ultrafast.transfer;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

public class ServiceActivity extends AppCompatActivity {

    private MaterialCheckBox chkWifi, chkBluetooth, chkLocation;
    private MaterialButton btnContinue;
    private boolean isSendMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_enabler);

        isSendMode = getIntent().getBooleanExtra("isSendMode", true);

        chkWifi = findViewById(R.id.chkWifi);
        chkBluetooth = findViewById(R.id.chkBluetooth);
        chkLocation = findViewById(R.id.chkLocation);
        btnContinue = findViewById(R.id.btnContinue);

        // Click listeners for each card to trigger settings
        findViewById(R.id.cardWifi).setOnClickListener(v -> {
            if (!isWifiEnabled()) startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        });

        findViewById(R.id.cardBluetooth).setOnClickListener(v -> {
            if (!isBluetoothEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        });

        findViewById(R.id.cardLocation).setOnClickListener(v -> {
            if (!isGpsEnabled()) startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        });

        btnContinue.setOnClickListener(v -> proceedToDiscovery());
        if(isAllServicesEnabled()) proceedToDiscovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();

        // Auto-proceed if you want, or just wait for the button click
        if (isAllServicesEnabled()) {
            btnContinue.setVisibility(View.VISIBLE);
        } else {
            btnContinue.setVisibility(View.GONE);
        }
    }

    private void updateUI() {
        chkWifi.setChecked(isWifiEnabled());
        chkBluetooth.setChecked(isBluetoothEnabled());
        chkLocation.setChecked(isGpsEnabled());
    }

    private boolean isWifiEnabled() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    private boolean isBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    private boolean isGpsEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private boolean isAllServicesEnabled() {
        return isWifiEnabled() && isBluetoothEnabled() && isGpsEnabled();
    }

    private void proceedToDiscovery() {
        Intent intent = new Intent(this, DiscoveryActivity.class);
        intent.putExtra("isSendMode", isSendMode);
        startActivity(intent);
        finish();
    }
}