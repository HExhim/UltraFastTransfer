package com.ultrafast.transfer;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
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
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ultrafast.transfer.db.AppDatabase;
import com.ultrafast.transfer.db.TransferRecord;
import com.ultrafast.transfer.network.discovery.Device;
import com.ultrafast.transfer.network.discovery.UdpDiscoveryManager;
import com.ultrafast.transfer.network.transfer.ParallelFileServer;

import java.util.ArrayList;
import java.util.List;

public class DiscoveryActivity extends AppCompatActivity implements WifiP2pManager.ConnectionInfoListener {

    private static final String TAG = "DiscoveryActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private WifiManager.MulticastLock multicastLock;
    private UdpDiscoveryManager udpDiscoveryManager;
    private DeviceAdapter deviceAdapter;

    private boolean isSendMode;
    private ArrayList<FileItem> selectedFiles;
    private boolean isDiscoveryStarted = false;

    private View pulse1, pulse2;
    private TextView tvScanningStatus;

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
    protected void onStart() {
        super.onStart();
        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            if (isHardwareReady()) {
                acquireMulticastLock();
                setupNetworkAndDiscovery();
            } else {
                showEnableServicesDialog();
            }
        } else {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private boolean isHardwareReady() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        LocationManager loc = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        return (bt == null || bt.isEnabled()) &&
                loc.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                wifi.isWifiEnabled();
    }

    private void setupNetworkAndDiscovery() {
        if (isDiscoveryStarted) return;
        isDiscoveryStarted = true;

        tvScanningStatus.setText(isSendMode ? "Searching for receivers..." : "Waiting for sender...");

        // 1. Server Instance (Singleton logic)
        ParallelFileServer server = ParallelFileServer.getInstance();
        server.startServer(this, new ParallelFileServer.ServerCallback() {
            @Override
            public void onHandshake(String name, String ip) {
                if (!isSendMode) runOnUiThread(() -> startTransferActivity(name, ip));
            }

            @Override
            public void onManifestReceived(List<ParallelFileServer.ManifestFile> files) {}

            @Override
            public void onFileReceived(String fileName, long size) {}

            @Override
            public void onProgress(String fileName, long current, long total) {}

            @Override
            public void onTransferComplete(String fileName) {

            }

            @Override
            public void onTransferFailed(String fileName, String error) {}

            @Override
            public void onServerStarted() {
                Log.d("Discovery", "Server is live");
            }

            @Override
            public void onError(String fileName, String error) {}
        });

        // 2. UDP Discovery
        udpDiscoveryManager = new UdpDiscoveryManager();
        if (isSendMode) {
            udpDiscoveryManager.discoverDevices(device -> runOnUiThread(() -> deviceAdapter.addDevice(device)));
        } else {
            udpDiscoveryManager.startListening(Build.MODEL);
        }

        // 3. Wi-Fi Direct
        p2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        p2pChannel = p2pManager.initialize(this, getMainLooper(), null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);

        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    updateP2pPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    p2pManager.requestConnectionInfo(p2pChannel, DiscoveryActivity.this);
                }
            }
        };
        registerReceiver(p2pReceiver, filter);

        if (isSendMode) startP2pDiscovery();
    }

    private void updateP2pPeers() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        p2pManager.requestPeers(p2pChannel, peers -> {
            for (android.net.wifi.p2p.WifiP2pDevice d : peers.getDeviceList()) {
                deviceAdapter.addDevice(new Device(d.deviceName, d.deviceAddress, true));
            }
        });
    }

    private void startP2pDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        p2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() { Log.d(TAG, "P2P Discovery Started"); }
            @Override
            public void onFailure(int r) { Log.e(TAG, "P2P Failed: " + r); }
        });
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info.groupFormed && info.groupOwnerAddress != null) {
            String ip = info.isGroupOwner ? "192.168.49.1" : info.groupOwnerAddress.getHostAddress();
            startTransferActivity("P2P Device", ip);
        }
    }

    private void startTransferActivity(String name, String ip) {
        if (isFinishing()) return;
        // Stop discovery before moving to transfer to free up Wi-Fi bandwidth
        stopDiscovery();

        Intent intent = new Intent(this, TransferActivity.class);
        intent.putExtra("deviceName", name);
        intent.putExtra("deviceIp", ip);
        intent.putExtra("isSendMode", isSendMode);
        intent.putParcelableArrayListExtra("selectedFiles", selectedFiles);
        startActivity(intent);
        finish();
    }

    private void stopDiscovery() {
        if (udpDiscoveryManager != null) udpDiscoveryManager.stop();
        if (p2pManager != null && p2pChannel != null) {
            p2pManager.stopPeerDiscovery(p2pChannel, null);
        }
        isDiscoveryStarted = false;
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("UltraFastLock");
            multicastLock.setReferenceCounted(true);
        }
        if (!multicastLock.isHeld()) multicastLock.acquire();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDiscovery();
        try { if (p2pReceiver != null) unregisterReceiver(p2pReceiver); } catch (Exception ignored) {}
    }

    private void initViews() {
        pulse1 = findViewById(R.id.pulse1);
        pulse2 = findViewById(R.id.pulse2);
        tvScanningStatus = findViewById(R.id.tvScanningStatus);
        ((TextView)findViewById(R.id.tvTitle)).setText(isSendMode ? "Send Mode" : "Receive Mode");

        RecyclerView rv = findViewById(R.id.rv_discovery_results);
        deviceAdapter = new DeviceAdapter(device -> {
            if (device.isP2p) connectToP2p(device);
            else startTransferActivity(device.name, device.ip);
        });
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(deviceAdapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void connectToP2p(Device device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.ip; // For P2p, Device.ip stores MAC
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        p2pManager.connect(p2pChannel, config, null);
    }

    private void startRadarAnimation() {
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.pulse_radar);
        pulse1.startAnimation(anim);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) pulse2.startAnimation(anim);
        }, 1000);
    }

    private void showEnableServicesDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Hardware Required")
                .setMessage("Please enable Wi-Fi, Location, and Bluetooth to continue.")
                .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)))
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();
    }
}