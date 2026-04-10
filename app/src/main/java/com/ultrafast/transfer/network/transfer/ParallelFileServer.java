package com.ultrafast.transfer.network.transfer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParallelFileServer {
    private static final int PORT = 9999;
    private static final String TAG = "FileServer";
    private static ParallelFileServer instance;

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()));
    private final ConcurrentHashMap<String, FileChannel> openFiles = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private boolean isRunning = false;

    public interface ConnectionListener {
        void onRemoteDeviceConnected(String name, String ip);
    }

    private ConnectionListener connectionListener;
    private ServerCallback serverCallback;

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void setServerCallback(ServerCallback callback) {
        this.serverCallback = callback;
    }

    public interface ServerCallback {
        void onManifestReceived(List<ManifestFile> files);
        void onFileReceived(String fileName, long size);
        void onProgress(String fileName, long current, long total);
        void onTransferComplete(String fileName);
        void onTransferFailed(String fileName, String error);
        void onServerStarted();
    }

    public static class ManifestFile {
        public String name;
        public long size;
        public ManifestFile(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    public static synchronized ParallelFileServer getInstance() {
        if (instance == null) instance = new ParallelFileServer();
        return instance;
    }

    public void startServer(Context context, ServerCallback callback) {
        this.serverCallback = callback;
        if (isRunning) {
            if (callback != null) callback.onServerStarted();
            return;
        }
        isRunning = true;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                if (serverCallback != null) serverCallback.onServerStarted();
                Log.d(TAG, "Server started on port " + PORT);

                while (isRunning) {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(30000);
                    executor.submit(() -> handleClientStream(client));
                }
            } catch (Exception e) {
                if (isRunning) Log.e(TAG, "Server error: " + e.getMessage());
            }
        }).start();
    }

    private void handleClientStream(Socket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            int headerId = in.readInt();

            if (headerId == 0) {
                // HANDSHAKE
                String deviceName = in.readUTF();
                String ip = socket.getInetAddress().getHostAddress();
                if (connectionListener != null) {
                    connectionListener.onRemoteDeviceConnected(deviceName, ip);
                }
            } else if (headerId == 1) {
                // MANIFEST
                int count = in.readInt();
                List<ManifestFile> manifest = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    manifest.add(new ManifestFile(in.readUTF(), in.readLong()));
                }
                if (serverCallback != null) {
                    serverCallback.onManifestReceived(manifest);
                }
            } else if (headerId == 2) {
                // FILE DATA
                long startOffset = in.readLong();
                long totalSize = in.readLong();
                String fileName = in.readUTF();

                if (serverCallback != null && startOffset == 0) {
                    serverCallback.onFileReceived(fileName, totalSize);
                }

                FileChannel fc = getOrCreateChannel(fileName, totalSize);
                if (fc != null) {
                    long received = fc.transferFrom(Channels.newChannel(in), startOffset, totalSize - startOffset);
                    if (serverCallback != null) {
                        serverCallback.onProgress(fileName, startOffset + received, totalSize);
                    }
                    if (startOffset + received >= totalSize) {
                        // We should probably check if ALL streams for this file are done
                        // but for simplicity, we rely on the last stream finishing
                        serverCallback.onTransferComplete(fileName);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Stream error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private FileChannel getOrCreateChannel(String fileName, long totalSize) {
        return openFiles.computeIfAbsent(fileName, name -> {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "UltraFast");
                if (!dir.exists()) dir.mkdirs();
                RandomAccessFile raf = new RandomAccessFile(new File(dir, name), "rw");
                raf.setLength(totalSize);
                return raf.getChannel();
            } catch (Exception e) {
                return null;
            }
        });
    }

    public void stopServer() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
            for (FileChannel fc : openFiles.values()) {
                if (fc != null) fc.close();
            }
            openFiles.clear();
        } catch (IOException ignored) {}
    }
}