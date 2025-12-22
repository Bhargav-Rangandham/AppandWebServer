package com.hotel.utilities;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class ServerConfig {
    // If detectIP() fails, replace with your static IP, e.g. "192.168.1.7"
    public static final String SERVER_IP = detectIP();

    private static String detectIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    String ip = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && ip.contains(".")) {
                        System.out.println("Detected server IP: " + ip + " (interface=" + ni.getDisplayName() + ")");
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // fallback
        System.out.println("Falling back to localhost for SERVER_IP. Replace with LAN IP if needed.");
        return "127.0.0.1";
    }
}
