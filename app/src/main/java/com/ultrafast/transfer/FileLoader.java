package com.ultrafast.transfer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileLoader {

    private final String TAG = "FileLoader";
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Context context;

    private static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;
    private static final int CHUNK_SIZE = 100; // Increased for better performance with large collections

    public FileLoader(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(THREAD_COUNT);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public interface FileLoadCallback {
        void onLoading();
        void onChunkLoaded(List<FileItem> chunk);
        void onComplete(List<FileItem> allFiles);
        void onError(Exception e);
    }

    public void loadFiles(File directory, FileFilter filter, FileLoadCallback callback) {
        callback.onLoading();
        executor.execute(() -> {
            try {
                if (directory == null || !directory.exists()) {
                    mainHandler.post(() -> callback.onComplete(new ArrayList<>()));
                    return;
                }

                File[] files = directory.listFiles(filter);
                if (files == null) {
                    mainHandler.post(() -> callback.onComplete(new ArrayList<>()));
                    return;
                }

                Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                
                List<FileItem> allItems = new ArrayList<>(files.length);
                for (int i = 0; i < files.length; i += CHUNK_SIZE) {
                    int end = Math.min(i + CHUNK_SIZE, files.length);
                    List<FileItem> chunk = new ArrayList<>(end - i);
                    for (int j = i; j < end; j++) {
                        chunk.add(FileItem.fromFile(files[j]));
                    }
                    allItems.addAll(chunk);
                    final List<FileItem> chunkToPost = new ArrayList<>(chunk);
                    mainHandler.post(() -> callback.onChunkLoaded(chunkToPost));
                }
                mainHandler.post(() -> callback.onComplete(allItems));

            } catch (Exception e) {
                Log.e(TAG, "Error loading files", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void loadInstalledApps(FileLoadCallback callback) {
        callback.onLoading();
        executor.execute(() -> {
            try {
                PackageManager pm = context.getPackageManager();
                
                Set<String> launchablePackages = new HashSet<>();
                Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> launchables = pm.queryIntentActivities(launchIntent, 0);
                for (ResolveInfo info : launchables) {
                    launchablePackages.add(info.activityInfo.packageName);
                }

                List<PackageInfo> packages = pm.getInstalledPackages(0);
                List<FileItem> allItems = new ArrayList<>();

                for (int i = 0; i < packages.size(); i += CHUNK_SIZE) {
                    int end = Math.min(i + CHUNK_SIZE, packages.size());
                    List<FileItem> chunk = new ArrayList<>();

                    for (int j = i; j < end; j++) {
                        PackageInfo pInfo = packages.get(j);
                        boolean isSystem = (pInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        
                        if (isSystem && !launchablePackages.contains(pInfo.packageName)) {
                            continue;
                        }

                        File appFile = new File(pInfo.applicationInfo.sourceDir);
                        if (appFile.exists()) {
                            String appName = pm.getApplicationLabel(pInfo.applicationInfo).toString();
                            FileItem item = new FileItem(
                                    Uri.fromFile(appFile),
                                    appName,
                                    appFile.length(),
                                    appFile.lastModified(),
                                    "apk",
                                    false,
                                    appFile.getAbsolutePath()
                            );
                            item.setSystem(isSystem);
                            chunk.add(item);
                        }
                    }

                    if (!chunk.isEmpty()) {
                        allItems.addAll(chunk);
                        final List<FileItem> chunkToPost = new ArrayList<>(chunk);
                        mainHandler.post(() -> callback.onChunkLoaded(chunkToPost));
                    }
                }
                mainHandler.post(() -> callback.onComplete(allItems));
            } catch (Exception e) {
                Log.e(TAG, "Error loading apps", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void scanMediaStore(String category, FileLoadCallback callback) {
        callback.onLoading();

        executor.execute(() -> {
            Uri contentUri;

            switch (category) {
                case "IMAGES":
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    break;
                case "VIDEOS":
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    break;
                case "MUSIC":
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    break;
                default:
                    contentUri = MediaStore.Files.getContentUri("external");
                    break;
            }

            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA
            };

            try (Cursor cursor = context.getContentResolver().query(
                    contentUri,
                    projection,
                    null,
                    null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
            )) {

                if (cursor == null) {
                    mainHandler.post(() -> callback.onComplete(new ArrayList<>()));
                    return;
                }

                int idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                int nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                int dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
                int dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);

                List<FileItem> chunk = new ArrayList<>();
                List<FileItem> all = new ArrayList<>();

                while (cursor.moveToNext()) {

                    long id = cursor.getLong(idIdx);
                    Uri uri = Uri.withAppendedPath(contentUri, String.valueOf(id));

                    String name = nameIdx != -1 ? cursor.getString(nameIdx) : "Unknown";
                    long size = sizeIdx != -1 ? cursor.getLong(sizeIdx) : 0;
                    String path = dataIdx != -1 ? cursor.getString(dataIdx) : null;

                    long date = (dateIdx != -1 ? cursor.getLong(dateIdx) : 0) * 1000;
                    if (date <= 0) date = System.currentTimeMillis();

                    String ext = "";
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) ext = name.substring(dot + 1);

                    FileItem item = new FileItem(
                            uri,
                            name,
                            size,
                            date,
                            ext,
                            false,
                            path
                    );

                    chunk.add(item);
                    all.add(item);

                    if (chunk.size() >= CHUNK_SIZE) {
                        List<FileItem> postChunk = new ArrayList<>(chunk);
                        mainHandler.post(() -> callback.onChunkLoaded(postChunk));
                        chunk.clear();
                    }
                }

                if (!chunk.isEmpty()) {
                    List<FileItem> postChunk = new ArrayList<>(chunk);
                    mainHandler.post(() -> callback.onChunkLoaded(postChunk));
                }

                mainHandler.post(() -> callback.onComplete(all));

            } catch (Exception e) {
                Log.e(TAG, "MediaStore scan error", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}