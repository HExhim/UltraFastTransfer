package com.ultrafast.transfer.network.discovery;

import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class UdpDiscoveryManager {
    private static final int PORT = 8888;
    private static final String TAG = "UdpDiscovery";

    private DatagramSocket listenSocket;
    private DatagramSocket discoverSocket;
    private volatile boolean isListening = false;
    private volatile boolean isDiscovering = false;
    private final Set<String> foundIps = new HashSet<>();

    public void startListening(String deviceName) {
        if (isListening) return;
        isListening = true;

        new Thread(() -> {
            try {
                listenSocket = new DatagramSocket(PORT, InetAddress.getByName("0.0.0.0"));
                listenSocket.setBroadcast(true);
                byte[] buffer = new byte[1024];
                while (isListening) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    listenSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());

                    if ("DISCOVER_REQUEST".equals(message)) {
                        byte[] response = ("DISCOVER_RESPONSE:" + deviceName).getBytes();
                        DatagramPacket responsePacket = new DatagramPacket(
                                response, response.length, packet.getAddress(), packet.getPort());
                        listenSocket.send(responsePacket);
                    }
                }
            } catch (Exception e) {
                if (isListening) Log.e(TAG, "Listener error", e);
            }
        }).start();
    }

    public void discoverDevices(Consumer<Device> onDeviceFound) {
        if (isDiscovering) return;
        isDiscovering = true;
        foundIps.clear();

        new Thread(() -> {
            try {
                discoverSocket = new DatagramSocket();
                discoverSocket.setBroadcast(true);
                discoverSocket.setSoTimeout(3000);
                byte[] request = "DISCOVER_REQUEST".getBytes();

                while (isDiscovering) {
                    sendDiscoveryBroadcast(discoverSocket, request);
                    long startTime = System.currentTimeMillis();
                    while (System.currentTimeMillis() - startTime < 3000) {
                        try {
                            byte[] buffer = new byte[1024];
                            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                            discoverSocket.receive(packet);

                            String message = new String(packet.getData(), 0, packet.getLength());
                            if (message.startsWith("DISCOVER_RESPONSE:")) {
                                String name = message.substring(18);
                                String ip = packet.getAddress().getHostAddress();
                                if (foundIps.add(ip)) {
                                    onDeviceFound.accept(new Device(name, ip, false));
                                }
                            }
                        } catch (java.net.SocketTimeoutException e) { break; }
                    }
                }
            } catch (Exception e) {
                if (isDiscovering) Log.e(TAG, "Discovery error", e);
            }
        }).start();
    }

    private void sendDiscoveryBroadcast(DatagramSocket socket, byte[] request) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null) {
                        socket.send(new DatagramPacket(request, request.length, broadcast, PORT));
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Broadcast error", e); }
    }

    public void stop() {
        isListening = false;
        isDiscovering = false;
        if (listenSocket != null) { listenSocket.close(); listenSocket = null; }
        if (discoverSocket != null) { discoverSocket.close(); discoverSocket = null; }
    }
}