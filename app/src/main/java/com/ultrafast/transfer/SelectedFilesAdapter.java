package com.ultrafast.transfer;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.ultrafast.transfer.databinding.ItemFileBrowseBinding;
import java.util.ArrayList;
import java.util.List;

public class SelectedFilesAdapter extends RecyclerView.Adapter<SelectedFilesAdapter.ViewHolder> {

    private final List<FileItem> selectedFiles = new ArrayList<>();
    private final OnItemRemovedListener listener;

    public interface OnItemRemovedListener {
        void onItemRemoved(FileItem item);
    }

    public SelectedFilesAdapter(OnItemRemovedListener listener) {
        this.listener = listener;
    }

    public void setFiles(List<FileItem> files) {
        selectedFiles.clear();
        selectedFiles.addAll(files);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemFileBrowseBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileItem item = selectedFiles.get(position);
        ThumbnailLoader loader = ThumbnailLoader.getInstance(holder.itemView.getContext());

        // Fix: Remove tint and padding for thumbnails so they show correctly
        if (item.isImage() || item.isVideo() || item.isApk() || item.isAudio()) {
            holder.binding.ivFileIcon.setImageTintList(null);
            holder.binding.ivFileIcon.setPadding(0, 0, 0, 0);
            holder.binding.ivFileIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        } else {
            // Restore tint and padding for generic file icons
            int color = ContextCompat.getColor(holder.itemView.getContext(), R.color.primary);
            holder.binding.ivFileIcon.setImageTintList(ColorStateList.valueOf(color));
            int padding = (int) (8 * holder.itemView.getContext().getResources().getDisplayMetrics().density);
            holder.binding.ivFileIcon.setPadding(padding, padding, padding, padding);
            holder.binding.ivFileIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        }

        loader.load(item, holder.binding.ivFileIcon);
        
        holder.binding.tvFileName.setText(item.getName());
        holder.binding.tvFileInfo.setText(formatSize(item.getSize()));
        holder.binding.cbSelect.setChecked(true);
        
        holder.binding.cbSelect.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos != RecyclerView.NO_POSITION && listener != null) {
                FileItem removedItem = selectedFiles.get(currentPos);
                listener.onItemRemoved(removedItem);
                selectedFiles.remove(currentPos);
                notifyItemRemoved(currentPos);
                notifyItemRangeChanged(currentPos, selectedFiles.size());
            }
        });
    }

    @Override
    public int getItemCount() {
        return selectedFiles.size();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ItemFileBrowseBinding binding;
        ViewHolder(ItemFileBrowseBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}