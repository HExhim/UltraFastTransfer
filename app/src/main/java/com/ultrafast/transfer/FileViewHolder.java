package com.ultrafast.transfer;

import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ultrafast.transfer.databinding.ItemFileMediaBinding;
import com.ultrafast.transfer.databinding.ItemFileMusicBinding;
import com.ultrafast.transfer.databinding.ItemFileStandardBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileViewHolder extends RecyclerView.ViewHolder {

    private final OnItemClickListener listener;

    private ItemFileMediaBinding mediaBinding;
    private ItemFileMusicBinding musicBinding;
    private ItemFileStandardBinding standardBinding;

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public FileViewHolder(ItemFileMediaBinding binding, OnItemClickListener listener) {
        super(binding.getRoot());
        this.mediaBinding = binding;
        this.listener = listener;
        setupClick();
    }

    public FileViewHolder(ItemFileMusicBinding binding, OnItemClickListener listener) {
        super(binding.getRoot());
        this.musicBinding = binding;
        this.listener = listener;
        setupClick();
    }

    public FileViewHolder(ItemFileStandardBinding binding, OnItemClickListener listener) {
        super(binding.getRoot());
        this.standardBinding = binding;
        this.listener = listener;
        setupClick();
    }

    private void setupClick() {
        itemView.setOnClickListener(v -> {
            int pos = getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onItemClick(pos);
            }
        });
    }

    public void bind(FileItem item, boolean isSelected) {
        ThumbnailLoader loader = ThumbnailLoader.getInstance(itemView.getContext());
        String sizeStr = Formatter.formatFileSize(itemView.getContext(), item.getSize());

        if (mediaBinding != null) {
            mediaBinding.tvFileName.setText(item.getName());
            mediaBinding.cbSelected.setChecked(isSelected);
            mediaBinding.tvFileSize.setText(sizeStr);
            mediaBinding.ivPlayIcon.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
            loader.load(item, mediaBinding.ivFileIcon);
        } else if (musicBinding != null) {
            musicBinding.tvFileName.setText(item.getName());
            String subText = item.getArtist() != null ? item.getArtist() : item.getFolderName();
            musicBinding.tvMusicAlbum.setText(subText);
            
            String dateStr = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date(item.getLastModified()));
            musicBinding.tvMusicSize.setText(String.format("%s • %s", sizeStr, dateStr));
            
            musicBinding.cbSelect.setChecked(isSelected);
            loader.load(item, musicBinding.ivAlbumThumbnail);
        } else if (standardBinding != null) {
            standardBinding.tvFileName.setText(item.getName());
            standardBinding.cbSelected.setChecked(isSelected);
            standardBinding.tvFileSize.setText(sizeStr);
            loader.load(item, standardBinding.ivFileIcon);
        }
    }
}