package com.ultrafast.transfer.network.transfer;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.*;
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
    private static final int PORT = 9999;
    private static final int NUM_STREAMS = 3;
    private final ExecutorService executor = Executors.newFixedThreadPool(NUM_STREAMS + 1);

    public interface ProgressListener {
        void onProgress(long current, long total);
        void onSuccess();
        void onError(String error);
    }

    public void sendHandshake(String ip, String deviceName) {
        executor.submit(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, PORT), 5000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(0); // Type: Handshake
                out.writeUTF(deviceName);
                out.flush();
            } catch (Exception e) {
                Log.e("TransferEngine", "Handshake failed", e);
            }
        });
    }

    public void sendManifest(String ip, List<ParallelFileServer.ManifestFile> files) {
        executor.submit(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, PORT), 5000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(1); // Type: Manifest
                out.writeInt(files.size());
                for (ParallelFileServer.ManifestFile file : files) {
                    out.writeUTF(file.name);
                    out.writeLong(file.size);
                }
                out.flush();
            } catch (Exception e) {
                Log.e("TransferEngine", "Manifest send failed", e);
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

                    // Header for File Data is now 2
                    ByteBuffer header = ByteBuffer.allocate(1024);
                    header.putInt(2); // headerId
                    header.putLong(start);
                    header.putLong(fileSize); // Total size for channel creation
                    byte[] nameBytes = fileName.getBytes();
                    header.putInt(nameBytes.length);
                    header.put(nameBytes);
                    header.flip();
                    while(header.hasRemaining()) socket.write(header);

                    try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                         FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                         FileChannel fc = fis.getChannel()) {

                        long transferred = 0;
                        while (transferred < length) {
                            long b = fc.transferTo(start + transferred, length - transferred, socket);
                            if (b <= 0) break;
                            transferred += b;
                            if (listener != null) listener.onProgress(totalSent.addAndGet(b), fileSize);
                        }
                    }
                    if (totalSent.get() >= fileSize && listener != null) listener.onSuccess();
                } catch (Exception e) { 
                    Log.e("TransferEngine", "Send error", e);
                    if (listener != null) listener.onError(e.getMessage()); 
                }
            });
        }
    }
}