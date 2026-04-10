package com.ultrafast.transfer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileLoader {

    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private static final int CHUNK_SIZE = 120;

    public FileLoader(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public interface FileLoadCallback {
        void onLoading();
        void onChunkLoaded(List<FileItem> chunk);
        void onComplete();
        void onError(Exception e);
    }

    public void scanMediaStore(String category, FileLoadCallback callback) {

        callback.onLoading();

        executor.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            Uri uri = getUri(category);

            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
            };

            String sort = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

            try (Cursor cursor = context.getContentResolver().query(
                    uri,
                    projection,
                    null,
                    null,
                    sort
            )) {

                if (cursor == null) {
                    postError(callback, new IllegalStateException("Cursor null"));
                    return;
                }

                int idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                int dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);

                List<FileItem> chunk = new ArrayList<>(CHUNK_SIZE);

                while (cursor.moveToNext()) {

                    long id = cursor.getLong(idIdx);
                    String name = cursor.getString(nameIdx);
                    long size = cursor.getLong(sizeIdx);
                    long date = cursor.getLong(dateIdx) * 1000L;

                    Uri itemUri = Uri.withAppendedPath(uri, String.valueOf(id));

                    FileItem item = new FileItem(
                            itemUri,
                            name,
                            size,
                            date,
                            getExt(name),
                            false,
                            itemUri.toString()
                    );

                    chunk.add(item);

                    if (chunk.size() >= CHUNK_SIZE) {
                        postChunk(callback, new ArrayList<>(chunk));
                        chunk.clear();
                    }
                }

                if (!chunk.isEmpty()) {
                    postChunk(callback, chunk);
                }

                postComplete(callback);

            } catch (Exception e) {
                postError(callback, e);
            }
        });
    }

    private Uri getUri(String category) {
        switch (category) {
            case "IMAGE":
                return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            case "VIDEO":
                return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            case "AUDIO":
                return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            default:
                return MediaStore.Files.getContentUri("external");
        }
    }

    private String getExt(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1).toLowerCase() : "";
    }

    private void postChunk(FileLoadCallback cb, List<FileItem> chunk) {
        mainHandler.post(() -> cb.onChunkLoaded(chunk));
    }

    private void postComplete(FileLoadCallback cb) {
        mainHandler.post(cb::onComplete);
    }

    private void postError(FileLoadCallback cb, Exception e) {
        mainHandler.post(() -> cb.onError(e));
    }
}