package com.ultrafast.transfer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ultrafast.transfer.databinding.ItemFileMediaBinding;
import com.ultrafast.transfer.databinding.ItemFileMusicBinding;
import com.ultrafast.transfer.databinding.ItemFileStandardBinding;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

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
        super(new AsyncDifferConfig.Builder<>(new BrowserDiffCallback())
                .setBackgroundThreadExecutor(Executors.newSingleThreadExecutor())
                .build());
        this.selectionListener = listener;
    }

    public void setOnFolderClickListener(OnFolderClickListener listener) {
        this.folderClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == BrowserItem.TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false));
        } else if (viewType == BrowserItem.TYPE_FILE_MEDIA) {
            ItemFileMediaBinding binding = ItemFileMediaBinding.inflate(inflater, parent, false);
            return new FileViewHolder(binding, this::onItemClick);
        } else if (viewType == BrowserItem.TYPE_FILE_MUSIC) {
            ItemFileMusicBinding binding = ItemFileMusicBinding.inflate(inflater, parent, false);
            return new FileViewHolder(binding, this::onItemClick);
        } else if (viewType == BrowserItem.TYPE_FILE_STANDARD) {
            ItemFileStandardBinding binding = ItemFileStandardBinding.inflate(inflater, parent, false);
            return new FileViewHolder(binding, this::onItemClick);
        } else {
            throw new IllegalArgumentException("Invalid view type");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        BrowserItem item = getItem(position);
        if (holder instanceof HeaderViewHolder hvh) {
            hvh.tvTitle.setText(item.getHeaderTitle());
            hvh.itemView.setOnClickListener(v -> selectionListener.onHeaderClicked(item.getHeaderTitle()));
            hvh.ivChevron.setRotation(item.isCollapsed() ? -90f : 0f);
            hvh.cbSelect.setOnCheckedChangeListener(null);
            hvh.cbSelect.setChecked(isSectionFullySelected(position));
            hvh.cbSelect.setOnClickListener(v -> toggleSectionSelection(position, ((CheckBox) v).isChecked()));
        } else if (holder instanceof FileViewHolder) {
            FileItem fileItem = item.getFileItem();
            ((FileViewHolder) holder).bind(fileItem, selectedPaths.contains(fileItem.getPath()));
        }
    }

    private boolean isSectionFullySelected(int headerPos) {
        int nextHeader = findNextHeader(headerPos);
        int itemCount = 0;
        int selectedInHeader = 0;
        for (int i = headerPos + 1; i < nextHeader; i++) {
            BrowserItem item = getItem(i);
            if (item.getType() != BrowserItem.TYPE_HEADER) {
                itemCount++;
                if (selectedPaths.contains(item.getFileItem().getPath())) {
                    selectedInHeader++;
                }
            }
        }
        return itemCount > 0 && itemCount == selectedInHeader;
    }

    private void toggleSectionSelection(int headerPos, boolean isSelected) {
        int nextHeader = findNextHeader(headerPos);
        for (int i = headerPos + 1; i < nextHeader; i++) {
            FileItem fileItem = getItem(i).getFileItem();
            if (fileItem != null && !fileItem.isDirectory()) {
                if (isSelected) selectedPaths.add(fileItem.getPath());
                else selectedPaths.remove(fileItem.getPath());
                selectionListener.onItemToggled(fileItem, isSelected);
            }
        }
        notifyItemRangeChanged(headerPos, nextHeader - headerPos);
        selectionListener.onSelectionChanged();
    }

    private int findNextHeader(int from) {
        for (int i = from + 1; i < getItemCount(); i++) {
            if (getItem(i).getType() == BrowserItem.TYPE_HEADER) return i;
        }
        return getItemCount();
    }

    private void onItemClick(int position) {
        BrowserItem item = getItem(position);
        if (item.getType() == BrowserItem.TYPE_HEADER) return;

        FileItem fileItem = item.getFileItem();
        if (fileItem.isDirectory()) {
            if (folderClickListener != null) {
                folderClickListener.onFolderClick(fileItem);
            }
            return;
        }

        boolean isSelectedNow;
        if (selectedPaths.contains(fileItem.getPath())) {
            selectedPaths.remove(fileItem.getPath());
            isSelectedNow = false;
        } else {
            selectedPaths.add(fileItem.getPath());
            isSelectedNow = true;
        }
        notifyItemChanged(position);

        int headerPos = findHeaderForPosition(position);
        if (headerPos != -1) notifyItemChanged(headerPos);

        selectionListener.onItemToggled(fileItem, isSelectedNow);
        selectionListener.onSelectionChanged();
    }

    private int findHeaderForPosition(int pos) {
        for (int i = pos; i >= 0; i--) {
            if (getItem(i).getType() == BrowserItem.TYPE_HEADER) return i;
        }
        return -1;
    }

    public void updateSelection(Set<String> newSelection) {
        this.selectedPaths.clear();
        this.selectedPaths.addAll(newSelection);
        // We don't call notifyDataSetChanged() here because it's usually followed by submitList
        // and we want DiffUtil to handle the updates efficiently.
        // However, if only selection changes, we might need a way to refresh.
        // For now, let's keep it minimal to see if it fixes the Davey! logs.
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selectedPaths);
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(int spanCount) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int viewType = getItemViewType(position);
                if (viewType == BrowserItem.TYPE_HEADER ||
                        viewType == BrowserItem.TYPE_FILE_MUSIC) {
                    return spanCount;
                }
                return 1;
            }
        };
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        CheckBox cbSelect;
        ImageView ivChevron;

        HeaderViewHolder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvHeaderTitle);
            cbSelect = v.findViewById(R.id.cbSelectSection);
            ivChevron = v.findViewById(R.id.ivChevron);
        }
    }

    static class BrowserDiffCallback extends DiffUtil.ItemCallback<BrowserItem> {
        @Override
        public boolean areItemsTheSame(@NonNull BrowserItem oldItem, @NonNull BrowserItem newItem) {
            if (oldItem.getType() != newItem.getType()) return false;
            if (oldItem.getType() == BrowserItem.TYPE_HEADER) {
                return oldItem.getHeaderTitle().equals(newItem.getHeaderTitle());
            } else {
                return oldItem.getFileItem().getUri().equals(newItem.getFileItem().getUri());
            }
        }

        @Override
        public boolean areContentsTheSame(@NonNull BrowserItem oldItem, @NonNull BrowserItem newItem) {
            return oldItem.equals(newItem);
        }
    }
}