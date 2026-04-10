package com.ultrafast.transfer;

import android.view.View;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.ultrafast.transfer.databinding.ItemFileMediaBinding;
import com.ultrafast.transfer.databinding.ItemFileMusicBinding;
import com.ultrafast.transfer.databinding.ItemFileStandardBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Future;

public class FileViewHolder extends RecyclerView.ViewHolder {

    private ItemFileMediaBinding mediaBinding;
    private ItemFileStandardBinding standardBinding;
    private ItemFileMusicBinding musicBinding;
    private final ThumbnailLoader thumbnailLoader;
    private Future<?> currentLoadTask;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());

    public FileViewHolder(ItemFileMediaBinding binding, OnItemClickListener listener) {
        super(binding.getRoot());
        this.mediaBinding = binding;
        this.thumbnailLoader = ThumbnailLoader.getInstance(binding.getRoot().getContext());

        binding.getRoot().setOnClickListener(v -> {
            int pos = getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onClick(pos);
            }
        });
    }

    public FileViewHolder(ItemFileStandardBinding binding, OnItemClickListener listener) {
        super(binding.getRoot());
        this.standardBinding = binding;
        this.thumbnailLoader = ThumbnailLoader.getInstance(binding.getRoot().getContext());

        binding.getRoot().setOnClickListener(v -> {
            int pos = getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onClick(pos);
            }
        });
    }

    public FileViewHolder(ItemFileMusicBinding binding, OnItemClickListener listener) {
        super(binding.getRoot());
        this.musicBinding = binding;
        this.thumbnailLoader = ThumbnailLoader.getInstance(binding.getRoot().getContext());

        binding.getRoot().setOnClickListener(v -> {
            int pos = getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onClick(pos);
            }
        });
    }

    public interface OnItemClickListener {
        void onClick(int position);
    }

    public void bind(FileItem item, boolean isSelected) {
        if (mediaBinding != null) {
            mediaBinding.tvFileName.setText(item.getName());
            mediaBinding.tvFileSize.setText(formatFileSize(item.getSize()));
            mediaBinding.cbSelected.setChecked(isSelected);
            mediaBinding.ivPlayIcon.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
            loadIntoImageView(item, mediaBinding.ivFileIcon);
        } else if (musicBinding != null) {
            musicBinding.tvFileName.setText(item.getName());

            StringBuilder subTitle = new StringBuilder();
            if (item.getArtist() != null && !item.getArtist().isEmpty() && !"<unknown>".equals(item.getArtist())) {
                subTitle.append(item.getArtist());
                if (item.getAlbum() != null && !item.getAlbum().isEmpty() && !"<unknown>".equals(item.getAlbum())) {
                    subTitle.append(" • ").append(item.getAlbum());
                }
            } else {
                subTitle.append(item.getFolderName());
            }
            musicBinding.tvMusicAlbum.setText(subTitle.toString());

            String info = formatFileSize(item.getSize()) + " • " + dateFormat.format(new Date(item.getLastModified()));
            musicBinding.tvMusicSize.setText(info);
            musicBinding.cbSelect.setChecked(isSelected);
            loadIntoImageView(item, musicBinding.ivAlbumThumbnail);
        } else if (standardBinding != null) {
            standardBinding.tvFileName.setText(item.getName());
            standardBinding.tvFileSize.setText(formatFileSize(item.getSize()));
            standardBinding.cbSelected.setChecked(isSelected);
            loadIntoImageView(item, standardBinding.ivFileIcon);
        }
    }

    private void loadIntoImageView(FileItem item, ImageView imageView) {
        if (currentLoadTask != null) {
            currentLoadTask.cancel(true);
            currentLoadTask = null;
        }

        imageView.setPadding(0, 0, 0, 0); 

        if (item.isImage() || item.isVideo()) {
            Glide.with(imageView.getContext())
                    .asBitmap()
                    .load(item.getUri())
                    .apply(new RequestOptions()
                            .format(DecodeFormat.PREFER_RGB_565) // Optimize memory/speed
                            .signature(new ObjectKey(item.getUri().toString() + item.getLastModified()))
                            .override(180, 180) // Reduce size for faster loading
                            .centerCrop()
                            .placeholder(getFileIcon(item.getExtension()))
                            .error(getFileIcon(item.getExtension()))
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)) // Cache resized version
                    .thumbnail(0.1f) // Faster low-res preview
                    .into(imageView);
        } else if (item.isApk()) {
            Glide.with(imageView.getContext()).clear(imageView);
            imageView.setImageResource(R.drawable.ic_android);
            currentLoadTask = thumbnailLoader.loadThumbnail(item, imageView);
        } else if (item.isAudio()) {
            Glide.with(imageView.getContext()).clear(imageView);
            imageView.setImageResource(R.drawable.ic_music);
            imageView.setPadding(10, 10, 10, 10);
            currentLoadTask = thumbnailLoader.loadThumbnail(item, imageView);
        } else {
            Glide.with(imageView.getContext()).clear(imageView);
            imageView.setImageResource(getFileIcon(item.getExtension()));
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024f);
        else if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024f * 1024f));
        else return String.format("%.1f GB", size / (1024f * 1024f * 1024f));
    }

    private int getFileIcon(String extension) {
        switch (extension.toLowerCase()) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
                return R.drawable.ic_image;
            case "mp4": case "avi": case "mkv": case "mov":
                return R.drawable.ic_video;
            case "mp3": case "wav": case "flac": case "m4a":
                return R.drawable.ic_music;
            case "pdf":
                return R.drawable.ic_pdf;
            case "apk":
                return R.drawable.ic_android;
            default:
                return R.drawable.ic_file;
        }
    }
}