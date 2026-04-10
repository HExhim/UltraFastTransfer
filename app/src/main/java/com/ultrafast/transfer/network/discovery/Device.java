package com.ultrafast.transfer.network.discovery;

public class Device {
    public String name;
    public String ip; // Can be IP address (LAN) or MAC address (P2P)
    public boolean isP2p;

    public Device(String name, String ip, boolean isP2p) {
        this.name = name;
        this.ip = ip;
        this.isP2p = isP2p;
    }
}