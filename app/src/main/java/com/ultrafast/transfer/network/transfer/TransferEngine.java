package com.ultrafast.transfer.network.transfer;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.ultrafast.transfer.db.AppDatabase;
import com.ultrafast.transfer.db.TransferRecord;

import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class TransferEngine {
    private static final String TAG = "TransferEngine";
    private static final int PORT = 9999;
    private static final int NUM_STREAMS = 4;
    private final ExecutorService executor = Executors.newFixedThreadPool(NUM_STREAMS + 2);

    // Track for history
    private String remoteDeviceName = "Unknown Receiver";

    public interface ProgressListener {
        void onProgress(long current, long total);
        void onSuccess();
        void onError(String error);
    }

    public void sendHandshake(String ip, String deviceName) {
        this.remoteDeviceName = deviceName; // Save for history record
        executor.submit(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, PORT), 5000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(0);
                out.writeUTF(deviceName);
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Handshake failed", e);
            }
        });
    }

    public void sendManifest(String ip, List<ParallelFileServer.ManifestFile> files) {
        executor.submit(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, PORT), 5000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(1);
                out.writeInt(files.size());
                for (ParallelFileServer.ManifestFile file : files) {
                    out.writeUTF(file.name);
                    out.writeLong(file.size);
                }
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Manifest send failed", e);
            }
        });
    }

    public void sendFile(Context context, Uri uri, String fileName, long fileSize, String ip, ProgressListener listener) {
        AtomicLong totalSent = new AtomicLong(0);
        long chunkSize = fileSize / NUM_STREAMS;

        for (int i = 0; i < NUM_STREAMS; i++) {
            final int streamId = i;
            executor.submit(() -> {
                long start = streamId * chunkSize;
                long length = (streamId == NUM_STREAMS - 1) ? (fileSize - start) : chunkSize;

                try (SocketChannel socket = SocketChannel.open()) {
                    socket.socket().setSendBufferSize(2 * 1024 * 1024);
                    socket.socket().setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(ip, PORT));

                    byte[] nameBytes = fileName.getBytes();
                    ByteBuffer header = ByteBuffer.allocate(4 + 8 + 8 + 4 + nameBytes.length);
                    header.putInt(2);
                    header.putLong(start);
                    header.putLong(length);
                    header.putInt(nameBytes.length);
                    header.put(nameBytes);
                    header.flip();

                    while (header.hasRemaining()) socket.write(header);

                    try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                         FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileChannel fc = fis.getChannel()) {

                        long transferred = 0;
                        while (transferred < length) {
                            long bytes = fc.transferTo(start + transferred, length - transferred, socket);
                            if (bytes <= 0) break;
                            transferred += bytes;
                            totalSent.addAndGet(bytes);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Stream " + streamId + " failed", e);
                    if (listener != null) listener.onError(e.getMessage());
                }
            });
        }

        // Monitoring thread with History Saving logic
        executor.submit(() -> {
            long lastReported = 0;
            while (totalSent.get() < fileSize) {
                long current = totalSent.get();
                if (listener != null && (current - lastReported > 102400)) {
                    listener.onProgress(current, fileSize);
                    lastReported = current;
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            // Finalize Progress
            if (listener != null) listener.onProgress(fileSize, fileSize);

            // SAVE TO HISTORY
            saveToHistory(context, fileName, fileSize);

            if (listener != null) listener.onSuccess();
        });
    }

    private void saveToHistory(Context context, String fileName, long fileSize) {
        new Thread(() -> {
            try {
                TransferRecord record = new TransferRecord();
                record.deviceName = remoteDeviceName;
                record.type = "SENT";
                record.fileName = fileName;
                record.fileSize = fileSize;
                record.timestamp = System.currentTimeMillis();
                record.status = "COMPLETED";

                AppDatabase.getInstance(context).historyDao().insert(record);
                Log.d(TAG, "History saved for: " + fileName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save history", e);
            }
        }).start();
    }
}