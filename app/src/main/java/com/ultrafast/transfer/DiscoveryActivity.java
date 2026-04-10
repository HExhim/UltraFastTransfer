package com.ultrafast.transfer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ultrafast.transfer.network.discovery.Device;
import com.ultrafast.transfer.network.discovery.UdpDiscoveryManager;
import com.ultrafast.transfer.network.transfer.ParallelFileServer;

import java.util.ArrayList;
import java.util.List;

public class DiscoveryActivity extends AppCompatActivity implements WifiP2pManager.ConnectionInfoListener {

    private static final String TAG = "DiscoveryActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 102;
    private static final int REQUEST_ENABLE_BT = 103;

    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private IntentFilter p2pIntentFilter;
    private WifiManager.MulticastLock multicastLock;
    private UdpDiscoveryManager udpDiscoveryManager;
    private DeviceAdapter deviceAdapter;
    private boolean isSendMode;
    private ArrayList<FileItem> selectedFiles;

    private View pulse1, pulse2;
    private TextView tvTitle, tvScanningStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_discovery_animation_view);

        isSendMode = getIntent().getBooleanExtra("isSendMode", true);
        selectedFiles = getIntent().getParcelableArrayListExtra("selectedFiles");

        initViews();
        startRadarAnimation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        acquireMulticastLock();
        if (checkAndRequestPermissions()) {
            if (checkSystemSettings()) {
                setupNetworkAndDiscovery();
            }
        }
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("UltraFastMulticastLock");
            multicastLock.setReferenceCounted(true);
        }
        if (!multicastLock.isHeld()) multicastLock.acquire();
    }

    private void initViews() {
        pulse1 = findViewById(R.id.pulse1);
        pulse2 = findViewById(R.id.pulse2);
        tvTitle = findViewById(R.id.tvTitle);
        tvScanningStatus = findViewById(R.id.tvScanningStatus);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvTitle.setText(isSendMode ? "Send Mode" : "Receive Mode");

        RecyclerView rvDevices = findViewById(R.id.rv_discovery_results);
        deviceAdapter = new DeviceAdapter(this::handleDeviceClick);
        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(deviceAdapter);
    }

    private void setupNetworkAndDiscovery() {
        if (udpDiscoveryManager != null) return;

        tvScanningStatus.setText(isSendMode ? "Searching for receivers..." : "Waiting for sender...");

        // 1. Setup Server Synchronization
        ParallelFileServer server = ParallelFileServer.getInstance();
        if (!isSendMode) {
            server.setConnectionListener((remoteName, remoteIp) -> {
                runOnUiThread(() -> startTransferActivity(remoteName, remoteIp));
            });
        }
        server.startServer(this, null);

        // 2. Setup UDP Discovery
        udpDiscoveryManager = new UdpDiscoveryManager();
        if (isSendMode) {
            udpDiscoveryManager.discoverDevices(device -> runOnUiThread(() -> deviceAdapter.addDevice(device)));
        } else {
            udpDiscoveryManager.startListening(Build.MODEL);
        }

        // 3. Setup Wi-Fi Direct (P2P)
        p2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        p2pChannel = p2pManager.initialize(this, getMainLooper(), null);

        p2pIntentFilter = new IntentFilter();
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        p2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    updateP2pPeers(); // This is the method we're defining below
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    p2pManager.requestConnectionInfo(p2pChannel, DiscoveryActivity.this);
                }
            }
        };

        registerReceiver(p2pReceiver, p2pIntentFilter);
        if (isSendMode) startP2pDiscovery();
    }

    // FIX: Added the missing method to handle P2P peer updates
    private void updateP2pPeers() {
        if (p2pManager == null) return;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        p2pManager.requestPeers(p2pChannel, peers -> {
            for (android.net.wifi.p2p.WifiP2pDevice p2pDevice : peers.getDeviceList()) {
                // Add device to list (deviceAddress acts as the "IP" for P2P connections)
                deviceAdapter.addDevice(new Device(p2pDevice.deviceName, p2pDevice.deviceAddress, true));
            }
        });
    }

    private void startP2pDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        p2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() { Log.d(TAG, "P2P Discovery started"); }
            @Override
            public void onFailure(int reason) { Log.e(TAG, "P2P Discovery failed: " + reason); }
        });
    }

    private void handleDeviceClick(Device device) {
        if (device.isP2p) {
            connectToP2pDevice(device);
        } else {
            startTransferActivity(device.name, device.ip);
        }
    }

    private void connectToP2pDevice(Device device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.ip;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        p2pManager.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() { Toast.makeText(DiscoveryActivity.this, "Connecting...", Toast.LENGTH_SHORT).show(); }
            @Override
            public void onFailure(int reason) { Toast.makeText(DiscoveryActivity.this, "Connect failed", Toast.LENGTH_SHORT).show(); }
        });
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info.groupFormed) {
            String targetIp = info.isGroupOwner ? "192.168.49.1" : info.groupOwnerAddress.getHostAddress();
            startTransferActivity("P2P Device", targetIp);
        }
    }

    private void startTransferActivity(String name, String ip) {
        if (isFinishing()) return;
        Intent intent = new Intent(this, TransferActivity.class);
        intent.putExtra("deviceName", name);
        intent.putExtra("deviceIp", ip);
        intent.putExtra("isSendMode", isSendMode);
        if (selectedFiles != null) {
            intent.putParcelableArrayListExtra("selectedFiles", selectedFiles);
        }
        startActivity(intent);
        finish();
    }

    private void startRadarAnimation() {
        Animation anim1 = AnimationUtils.loadAnimation(this, R.anim.pulse_radar);
        pulse1.startAnimation(anim1);
        new Handler().postDelayed(() -> { if (!isFinishing()) pulse2.startAnimation(anim1); }, 1000);
    }

    private boolean checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private boolean checkSystemSettings() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        LocationManager loc = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!wifi.isWifiEnabled()) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return false;
        }
        if (!loc.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return false;
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (udpDiscoveryManager != null) udpDiscoveryManager.stop();
        try {
            if (p2pReceiver != null) unregisterReceiver(p2pReceiver);
        } catch (Exception ignored) {}
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }
}