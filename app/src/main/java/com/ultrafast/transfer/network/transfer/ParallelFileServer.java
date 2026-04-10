package com.ultrafast.transfer.network.transfer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.ultrafast.transfer.db.AppDatabase;
import com.ultrafast.transfer.db.TransferRecord;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ParallelFileServer {
    private static final String TAG = "UltraFastServer";
    private static final int PORT = 9999;
    private static ParallelFileServer instance;

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors() * 2));

    private final ConcurrentHashMap<String, AtomicLong> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sizeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileChannel> fileCache = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private ServerSocketChannel serverChannel;
    private ServerCallback serverCallback;
    private ConnectionListener connectionListener;
    private Context context;
    private String remoteDeviceName = "Unknown Device";

    public interface ConnectionListener {
        void onRemoteDeviceConnected(String name, String ip);
    }
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public interface ServerCallback {
        void onHandshake(String deviceName, String ip);

        void onManifestReceived(List<ManifestFile> files);

        void onFileReceived(String fileName, long size);

        void onProgress(String fileName, long current, long total);

        void onTransferComplete(String fileName);

        void onTransferFailed(String fileName, String error);

        void onServerStarted();

        void onError(String fileName, String error);
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
        this.context = context.getApplicationContext(); // Use App Context to prevent leaks
        this.serverCallback = callback;
        if (running) {
            if (callback != null) callback.onServerStarted();
            return;
        }
        running = true;

        new Thread(() -> {
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress(PORT));
                if (serverCallback != null) serverCallback.onServerStarted();

                while (running) {
                    SocketChannel client = serverChannel.accept();
                    client.socket().setReceiveBufferSize(2 * 1024 * 1024);
                    client.socket().setTcpNoDelay(true);
                    executor.submit(() -> handleClient(client));
                }
            } catch (IOException e) {
                if (running) Log.e(TAG, "Server error: " + e.getMessage());
            }
        }).start();
    }

    private void handleClient(SocketChannel socket) {
        try {
            ByteBuffer header = ByteBuffer.allocate(1024);
            int bytesRead = socket.read(header);
            if (bytesRead < 4) return;
            header.flip();

            int type = header.getInt();

            if (type == 0) { // HANDSHAKE
                byte[] data = new byte[header.remaining()];
                header.get(data);
                this.remoteDeviceName = new String(data).trim();
                String ip = socket.getRemoteAddress().toString();

                if (connectionListener != null)
                    connectionListener.onRemoteDeviceConnected(remoteDeviceName, ip);
                if (serverCallback != null) serverCallback.onHandshake(remoteDeviceName, ip);

            } else if (type == 1) { // MANIFEST (Crucial for tracking sizes)
                // Logic to read manifest list would go here if needed,
                // but usually handled by populating sizeMap during Type 2 start
            } else if (type == 2) { // FILE DATA
                long startOffset = header.getLong();
                long chunkLength = header.getLong();
                int nameLen = header.getInt();
                byte[] nameBytes = new byte[nameLen];
                header.get(nameBytes);
                String fileName = new String(nameBytes);

                // Populate total size tracking on first chunk
                if (startOffset == 0) {
                    sizeMap.put(fileName, chunkLength); // In multi-stream, chunkLength here = total file size
                    if (serverCallback != null)
                        serverCallback.onFileReceived(fileName, chunkLength);
                }

                receiveFileData(socket, fileName, startOffset, chunkLength);
            }
        } catch (Exception e) {
            if (serverCallback != null) serverCallback.onError("Unknown", e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void receiveFileData(SocketChannel socket, String fileName, long start, long length) throws IOException {
        FileChannel fc = getOrCreateChannel(fileName);
        if (fc == null) return;

        progressMap.putIfAbsent(fileName, new AtomicLong(0));
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 256);
        long receivedInStream = 0;

        while (receivedInStream < length) {
            buffer.clear();
            int read = socket.read(buffer);
            if (read == -1) break;

            buffer.flip();
            fc.write(buffer, start + receivedInStream);
            receivedInStream += read;

            long totalNow = progressMap.get(fileName).addAndGet(read);
            if (serverCallback != null) {
                serverCallback.onProgress(fileName, totalNow, sizeMap.getOrDefault(fileName, length));
            }
        }
        checkCompletion(fileName);
    }

    private FileChannel getOrCreateChannel(String fileName) {
        return fileCache.computeIfAbsent(fileName, name -> {
            try {
                // Logic to save in Downloads/UltraFast
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "UltraFast");

                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                }

                File file = new File(dir, name);
                // "rw" opens for reading and writing; creates the file if it doesn't exist
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                return raf.getChannel();
            } catch (Exception e) {
                Log.e(TAG, "Error creating FileChannel for " + fileName + ": " + e.getMessage());
                return null;
            }
        });
    }

    private synchronized void checkCompletion(String fileName) {
        AtomicLong progress = progressMap.get(fileName);
        Long totalSize = sizeMap.get(fileName);

        if (totalSize != null && progress != null && progress.get() >= totalSize) {
            try {
                FileChannel fc = fileCache.remove(fileName);
                if (fc != null) fc.close();

                // SAVE TO HISTORY
                saveToHistory(fileName, totalSize);

                if (serverCallback != null) serverCallback.onTransferComplete(fileName);

                // Cleanup maps
                progressMap.remove(fileName);
                sizeMap.remove(fileName);
            } catch (IOException ignored) {
            }
        }
    }

    private void saveToHistory(String fileName, long size) {
        if (context == null) return;
        new Thread(() -> {
            TransferRecord record = new TransferRecord();
            record.deviceName = remoteDeviceName;
            record.type = "RECEIVED";
            record.fileName = fileName;
            record.fileSize = size;
            record.timestamp = System.currentTimeMillis();
            record.status = "COMPLETED";
            AppDatabase.getInstance(context).historyDao().insert(record);
        }).start();
    }

    // ... (keep getOrCreateChannel and stopServer as they were)
}