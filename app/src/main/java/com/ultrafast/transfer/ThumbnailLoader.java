package com.ultrafast.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThumbnailLoader {

    private static volatile ThumbnailLoader instance;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final LruCache<String, Bitmap> cache;
    private final Map<String, Future<?>> loadingTasks;
    private final Context context;

    public static ThumbnailLoader getInstance(Context context) {
        if (instance == null) {
            synchronized (ThumbnailLoader.class) {
                if (instance == null) {
                    instance = new ThumbnailLoader(context);
                }
            }
        }
        return instance;
    }

    private ThumbnailLoader(Context context) {
        this.context = context.getApplicationContext();
        int cores = Runtime.getRuntime().availableProcessors();
        this.executor = Executors.newFixedThreadPool(Math.max(2, Math.min(cores, 4)));
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.loadingTasks = new ConcurrentHashMap<>();

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8; // Slightly smaller cache
        this.cache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public Future<?> loadThumbnail(FileItem item, ImageView target) {
        final String uriString = item.getUri().toString();
        target.setTag(uriString);

        Bitmap cached = cache.get(uriString);
        if (cached != null) {
            target.setImageBitmap(cached);
            return null;
        }

        Future<?> future = executor.submit(() -> {
            try {
                Bitmap bitmap = null;
                Uri uri = item.getUri();
                
                if (item.isApk()) {
                    bitmap = getApkIcon(item);
                } else if (item.isVideo()) {
                    bitmap = getVideoThumbnail(item);
                } else if (item.isAudio()) {
                    bitmap = getAudioAlbumArt(uri);
                } else if (item.isImage()) {
                    bitmap = decodeSampledBitmapFromUri(uri, 120, 120);
                }

                if (bitmap != null) {
                    cache.put(uriString, bitmap);
                    final Bitmap finalBitmap = bitmap;
                    mainHandler.post(() -> {
                        if (uriString.equals(target.getTag())) {
                            target.setImageBitmap(finalBitmap);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("ThumbnailLoader", "Failed to load: " + uriString, e);
            } finally {
                loadingTasks.remove(uriString);
            }
        });

        loadingTasks.put(uriString, future);
        return future;
    }

    private Bitmap decodeSampledBitmapFromUri(Uri uri, int reqW, int reqH) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
            
            opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            
            // Re-open stream to decode
            try (InputStream is2 = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is2, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private Bitmap getVideoThumbnail(FileItem item) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(item.getUri(), new Size(120, 120), null);
            } else {
                // Fallback for older versions if it's a file URI
                String path = item.getPath();
                if (path != null && !path.startsWith("content://")) {
                    return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
                }
            }
        } catch (Exception e) {
            Log.e("ThumbnailLoader", "Video thumbnail error", e);
        }
        return null;
    }

    private Bitmap getAudioAlbumArt(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            byte[] art = retriever.getEmbeddedPicture();
            if (art != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                return BitmapFactory.decodeByteArray(art, 0, art.length, options);
            }
        } catch (Exception e) {
            // No album art
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Bitmap getApkIcon(FileItem item) {
        String path = item.getPath();
        if (path == null || path.startsWith("content://")) {
            // Scoped storage makes this hard. If it's an installed app, we can get it via package name
            // But if it's a file picked via SAF, we might need a temporary file.
            // For now, we assume path is available if it's an APK we scanned or from File explorer.
            return null; 
        }
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(path, 0);
            if (info != null) {
                info.applicationInfo.sourceDir = path;
                info.applicationInfo.publicSourceDir = path;
                Drawable icon = info.applicationInfo.loadIcon(pm);
                return drawableToBitmap(icon);
            }
        } catch (Exception e) {
            Log.e("ThumbnailLoader", "APK icon error: " + path);
        }
        return null;
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }
}