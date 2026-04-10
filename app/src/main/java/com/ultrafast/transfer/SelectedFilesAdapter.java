package com.ultrafast.transfer;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
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
        holder.binding.tvFileName.setText(item.getName());
        holder.binding.tvFileInfo.setText(formatSize(item.getSize()));
        holder.binding.cbSelect.setChecked(true);
        holder.binding.cbSelect.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemRemoved(item);
                selectedFiles.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, selectedFiles.size());
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