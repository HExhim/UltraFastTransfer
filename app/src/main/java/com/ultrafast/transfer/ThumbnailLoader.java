package com.ultrafast.transfer;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optimized thumbnail loader using Glide for media and a custom cache for APK icons.
 */
public class ThumbnailLoader {

    private static volatile ThumbnailLoader instance;

    private final Context context;
    private final ExecutorService executor;
    private final PackageManager packageManager;
    private final LruCache<String, Bitmap> iconCache;

    public static ThumbnailLoader getInstance(Context context) {
        if (instance == null) {
            synchronized (ThumbnailLoader.class) {
                if (instance == null) {
                    instance = new ThumbnailLoader(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ThumbnailLoader(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.executor = Executors.newFixedThreadPool(2);

        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.iconCache = new LruCache<String, Bitmap>(maxMem / 16) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void load(FileItem item, ImageView target) {
        String key = item.getUri().toString();
        target.setTag(key);

        int placeholderRes = getPlaceholderRes(item);

        // 1. VIDEOS
        if (item.isVideo()) {
            Glide.with(target)
                    .asBitmap()
                    .load(item.getUri())
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .apply(new RequestOptions().frame(1000000))
                    .transform(new CenterCrop(), new RoundedCorners(12))
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .into(target);
            return;
        }

        // 2. IMAGES
        if (item.isImage()) {
            Glide.with(target)
                    .load(item.getUri())
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .transform(new CenterCrop(), new RoundedCorners(12))
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .into(target);
            return;
        }

        // 3. AUDIO (Album Art)
        if (item.isAudio()) {
            Object model = item.getUri();
            if (item.getAlbumId() != -1) {
                model = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        item.getAlbumId()
                );
            }
            
            Glide.with(target)
                    .load(model)
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .transform(new CenterCrop(), new RoundedCorners(12))
                    .fallback(placeholderRes)
                    .into(target);
            return;
        }

        // 4. APKs (Installed & Uninstalled)
        if (item.isApk()) {
            Bitmap cached = iconCache.get(key);
            if (cached != null) {
                Glide.with(target).clear(target);
                target.setImageBitmap(cached);
                return;
            }

            target.setImageResource(placeholderRes);

            executor.execute(() -> {
                Bitmap bitmap = loadApkIcon(item);
                if (bitmap != null) {
                    iconCache.put(key, bitmap);
                    target.post(() -> {
                        if (key.equals(target.getTag())) {
                            Glide.with(target).clear(target);
                            target.setImageBitmap(bitmap);
                        }
                    });
                }
            });
            return;
        }

        // 5. OTHERS (Folders, PDFs, Files)
        Glide.with(target).clear(target);
        target.setImageResource(placeholderRes);
    }

    private int getPlaceholderRes(FileItem item) {
        if (item.isDirectory()) return R.drawable.ic_folder;
        if (item.isImage()) return R.drawable.ic_image;
        if (item.isVideo()) return R.drawable.ic_video;
        if (item.isAudio()) return R.drawable.ic_music;
        if (item.isApk()) return R.drawable.ic_android;
        if ("pdf".equalsIgnoreCase(item.getExtension())) return R.drawable.ic_pdf;
        return R.drawable.ic_file;
    }

    private Bitmap loadApkIcon(FileItem item) {
        try {
            // Check if it's an installed app first
            if (item.getPackageName() != null) {
                Drawable icon = packageManager.getApplicationIcon(item.getPackageName());
                return drawableToBitmap(icon);
            }

            // Fallback to loading from APK file path
            String path = item.getPath();
            if (path == null) return null;

            File file = new File(path);
            if (file.exists()) {
                PackageInfo pkgInfo = packageManager.getPackageArchiveInfo(path, 0);
                if (pkgInfo != null) {
                    pkgInfo.applicationInfo.sourceDir = path;
                    pkgInfo.applicationInfo.publicSourceDir = path;
                    Drawable icon = pkgInfo.applicationInfo.loadIcon(packageManager);
                    return drawableToBitmap(icon);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
        int w = Math.max(1, drawable.getIntrinsicWidth());
        int h = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bitmap;
    }
}