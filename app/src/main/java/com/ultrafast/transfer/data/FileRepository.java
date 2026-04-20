package com.ultrafast.transfer.data;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import com.ultrafast.transfer.FileItem;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileRepository {
    private final Context context;

    public FileRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<FileItem> getFilesByCategory(String category, String subFilter) {
        if ("APPS".equalsIgnoreCase(category)) {
            if ("Not Installed".equalsIgnoreCase(subFilter)) {
                return getApkFiles();
            }
            return getInstalledApps();
        }

        List<FileItem> result = new ArrayList<>();
        Uri collection;
        String selection = null;
        String[] selectionArgs = null;

        String cat = category.toUpperCase();
        if (cat.contains("IMAGE") || cat.contains("PHOTO")) {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else if (cat.contains("VIDEO")) {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else if (cat.contains("MUSIC") || cat.contains("AUDIO")) {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        } else {
            collection = MediaStore.Files.getContentUri("external");
        }

        String sort = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        try (Cursor cursor = context.getContentResolver().query(collection, null, selection, selectionArgs, sort)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE);
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
                
                // Audio specific columns
                int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);

                // Fetch Bucket Name for Folder Grouping
                int bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    Uri contentUri = ContentUris.withAppendedId(collection, id);
                    long dateModified = cursor.getLong(dateCol) * 1000L;

                    FileItem item = new FileItem(
                            contentUri,
                            cursor.getString(nameCol),
                            cursor.getLong(sizeCol),
                            dateModified,
                            cursor.getString(mimeCol),
                            false,
                            cursor.getString(dataCol)
                    );

                    if (bucketCol != -1) {
                        String bucketName = cursor.getString(bucketCol);
                        if (bucketName != null) item.setFolderName(bucketName);
                    }
                    
                    if (albumIdCol != -1) {
                        item.setAlbumId(cursor.getLong(albumIdCol));
                    }
                    if (artistCol != -1) {
                        item.setArtist(cursor.getString(artistCol));
                    }
                    if (albumCol != -1) {
                        item.setAlbum(cursor.getString(albumCol));
                    }

                    result.add(item);
                    if (result.size() >= 3000) break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private List<FileItem> getInstalledApps() {
        List<FileItem> items = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchables = pm.queryIntentActivities(intent, 0);
        Set<String> launchablePackages = new HashSet<>();
        for (ResolveInfo info : launchables) {
            launchablePackages.add(info.activityInfo.packageName);
        }

        List<PackageInfo> packages = pm.getInstalledPackages(0);
        for (PackageInfo pkg : packages) {
            boolean isSystem = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystem = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            
            if (!isSystem || isUpdatedSystem || launchablePackages.contains(pkg.packageName)) {
                File apk = new File(pkg.applicationInfo.sourceDir);
                if (apk.exists()) {
                    FileItem item = new FileItem(
                            Uri.fromFile(apk),
                            pm.getApplicationLabel(pkg.applicationInfo).toString(),
                            apk.length(),
                            apk.lastModified(),
                            "application/vnd.android.package-archive",
                            false,
                            apk.getAbsolutePath()
                    );
                    item.setPackageName(pkg.packageName);
                    item.setSystem(isSystem && !isUpdatedSystem);
                    item.setFolderName(item.isSystem() ? "System Apps" : "User Apps");
                    items.add(item);
                }
            }
        }
        return items;
    }

    private List<FileItem> getApkFiles() {
        List<FileItem> result = new ArrayList<>();
        Uri collection = MediaStore.Files.getContentUri("external");
        String selection = MediaStore.Files.FileColumns.DATA + " LIKE '%.apk'";
        String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        try (Cursor cursor = context.getContentResolver().query(collection, null, selection, null, sortOrder)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    Uri contentUri = ContentUris.withAppendedId(collection, id);
                    long dateModified = cursor.getLong(dateCol) * 1000L;
                    String path = cursor.getString(dataCol);

                    FileItem item = new FileItem(
                            contentUri,
                            cursor.getString(nameCol),
                            cursor.getLong(sizeCol),
                            dateModified,
                            "application/vnd.android.package-archive",
                            false,
                            path
                    );
                    result.add(item);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
}