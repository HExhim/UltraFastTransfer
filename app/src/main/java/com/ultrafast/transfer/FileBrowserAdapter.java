package com.ultrafast.transfer;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ultrafast.transfer.databinding.ItemFileMediaBinding;
import com.ultrafast.transfer.databinding.ItemFileMusicBinding;
import com.ultrafast.transfer.databinding.ItemFileStandardBinding;
import com.ultrafast.transfer.databinding.ItemSectionHeaderBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileBrowserAdapter extends ListAdapter<BrowserItem, RecyclerView.ViewHolder> {

    private final Set<String> selectedPaths = new HashSet<>();
    private final OnSelectionChangedListener selectionListener;
    private OnFolderClickListener folderClickListener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged();
        void onItemToggled(FileItem item, boolean isSelected);
        void onHeaderClicked(String headerTitle);
    }

    public interface OnFolderClickListener {
        void onFolderClick(FileItem folder);
    }

    public FileBrowserAdapter(OnSelectionChangedListener listener) {
        super(DIFF_CALLBACK);
        this.selectionListener = listener;
    }

    public void setOnFolderClickListener(OnFolderClickListener listener) {
        this.folderClickListener = listener;
    }

    public void setSelectedPaths(Set<String> paths) {
        selectedPaths.clear();
        selectedPaths.addAll(paths);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case BrowserItem.TYPE_HEADER:
                return new HeaderViewHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false));
            case BrowserItem.TYPE_FILE_MEDIA:
                return new FileViewHolder(ItemFileMediaBinding.inflate(inflater, parent, false), this::onItemClick);
            case BrowserItem.TYPE_FILE_MUSIC:
                return new FileViewHolder(ItemFileMusicBinding.inflate(inflater, parent, false), this::onItemClick);
            default:
                return new FileViewHolder(ItemFileStandardBinding.inflate(inflater, parent, false), this::onItemClick);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        BrowserItem item = getItem(position);

        if (holder instanceof HeaderViewHolder hvh) {
            hvh.bind(item, isGroupSelected(position), selectionListener, isChecked -> toggleGroup(position, isChecked));
        } else if (holder instanceof FileViewHolder fvh) {
            FileItem fileItem = item.getFileItem();
            if (fileItem != null) {
                fvh.bind(fileItem, selectedPaths.contains(fileItem.getUri().toString()));
            }
        }
    }

    private boolean isGroupSelected(int headerPosition) {
        int count = 0;
        int selectedCount = 0;
        for (int i = headerPosition + 1; i < getItemCount(); i++) {
            BrowserItem item = getItem(i);
            if (item.getType() == BrowserItem.TYPE_HEADER) break;
            if (item.getFileItem() != null) {
                count++;
                if (selectedPaths.contains(item.getFileItem().getUri().toString())) {
                    selectedCount++;
                }
            }
        }
        return count > 0 && count == selectedCount;
    }

    private void toggleGroup(int headerPosition, boolean selected) {
        List<FileItem> affectedItems = new ArrayList<>();
        for (int i = headerPosition + 1; i < getItemCount(); i++) {
            BrowserItem item = getItem(i);
            if (item.getType() == BrowserItem.TYPE_HEADER) break;
            FileItem fileItem = item.getFileItem();
            if (fileItem != null) {
                String key = fileItem.getUri().toString();
                if (selected) {
                    if (selectedPaths.add(key)) affectedItems.add(fileItem);
                } else {
                    if (selectedPaths.remove(key)) affectedItems.add(fileItem);
                }
            }
        }
        
        notifyDataSetChanged();

        if (selectionListener != null) {
            for (FileItem item : affectedItems) {
                selectionListener.onItemToggled(item, selected);
            }
            selectionListener.onSelectionChanged();
        }
    }

    private void onItemClick(int position) {
        BrowserItem item = getItem(position);
        if (item == null || item.getFileItem() == null) return;

        FileItem fileItem = item.getFileItem();
        if (fileItem.isDirectory()) {
            if (folderClickListener != null) folderClickListener.onFolderClick(fileItem);
            return;
        }

        String key = fileItem.getUri().toString();
        boolean selected = !selectedPaths.contains(key);
        
        if (selected) selectedPaths.add(key);
        else selectedPaths.remove(key);

        notifyItemChanged(position);
        
        for (int i = position; i >= 0; i--) {
            if (getItem(i).getType() == BrowserItem.TYPE_HEADER) {
                notifyItemChanged(i);
                break;
            }
        }

        if (selectionListener != null) {
            selectionListener.onItemToggled(fileItem, selected);
            selectionListener.onSelectionChanged();
        }
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(int spanCount) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int type = getItemViewType(position);
                return (type == BrowserItem.TYPE_HEADER || type == BrowserItem.TYPE_FILE_MUSIC) ? spanCount : 1;
            }
        };
    }

    private static final DiffUtil.ItemCallback<BrowserItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<BrowserItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull BrowserItem oldItem, @NonNull BrowserItem newItem) {
            if (oldItem.getType() != newItem.getType()) return false;
            if (oldItem.getType() == BrowserItem.TYPE_HEADER) {
                return oldItem.getHeaderTitle().equals(newItem.getHeaderTitle());
            }
            return oldItem.getFileItem() != null && newItem.getFileItem() != null &&
                    oldItem.getFileItem().getUri().equals(newItem.getFileItem().getUri());
        }

        @Override
        public boolean areContentsTheSame(@NonNull BrowserItem oldItem, @NonNull BrowserItem newItem) {
            return oldItem.equals(newItem);
        }
    };

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final ItemSectionHeaderBinding binding;

        HeaderViewHolder(ItemSectionHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(BrowserItem item, boolean isSelected, OnSelectionChangedListener listener, OnCheckedChangeListener checkListener) {
            binding.tvHeaderTitle.setText(item.getHeaderTitle());
            binding.tvHeaderCount.setText(item.getHeaderCount());
            binding.ivChevron.animate().rotation(item.isCollapsed() ? -90f : 0f).setDuration(200).start();
            
            binding.cbSelectSection.setOnCheckedChangeListener(null);
            binding.cbSelectSection.setChecked(isSelected);
            binding.cbSelectSection.setOnCheckedChangeListener((v, checked) -> checkListener.onCheckedChanged(checked));

            binding.headerContainer.setOnClickListener(v -> {
                if (listener != null) listener.onHeaderClicked(item.getHeaderTitle());
            });
        }

        interface OnCheckedChangeListener {
            void onCheckedChanged(boolean checked);
        }
    }
}