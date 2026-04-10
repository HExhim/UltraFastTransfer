package com.ultrafast.transfer;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.ultrafast.transfer.databinding.ActivityFileBrowserBinding;
import com.ultrafast.transfer.databinding.LayoutSelectedFilesSheetBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileBrowserActivity extends AppCompatActivity {

    private ActivityFileBrowserBinding binding;
    private FileBrowserAdapter fileAdapter;
    private FileBrowserViewModel viewModel;
    private TextView[] tabViews;

    private final Map<String, FileItem> selected = new HashMap<>();

    enum Category {FILES, VIDEOS, APPS, IMAGES, MUSIC}

    static class State {
        Category category = Category.APPS;
        String subFilter = "Installed";
        File directory = Environment.getExternalStorageDirectory();
        boolean explorer = false;
    }

    private final State state = new State();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFileBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(FileBrowserViewModel.class);
        tabViews = new TextView[]{binding.tabFiles, binding.tabVideos, binding.tabApps, binding.tabPhotos, binding.tabMusic};

        setupRecycler();
        setupTabs();
        setupDashboard();
        setupBottomBar();

        viewModel.getIsLoading().observe(this, loading ->
                binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));

        viewModel.getActiveItems().observe(this, items -> {
            if (items == null || items.isEmpty()) {
                fileAdapter.submitList(Collections.emptyList());
                binding.rvFiles.setVisibility(View.GONE);
                // binding.emptyView.setVisibility(View.VISIBLE); // if you have one
            } else {
                binding.rvFiles.setVisibility(View.VISIBLE);
                fileAdapter.submitList(items);
            }
        });

        // Start with APPS/Installed
        updateState(Category.APPS, true, "Installed", null);
        handleBack();
    }

    private void setupRecycler() {
        GridLayoutManager lm = new GridLayoutManager(this, 3);
        binding.rvFiles.setHasFixedSize(true);
        binding.rvFiles.setItemViewCacheSize(40); // Increased for smoother scrolling with groups

        fileAdapter = new FileBrowserAdapter(new FileBrowserAdapter.OnSelectionChangedListener() {
            @Override
            public void onSelectionChanged() {
                updateSelection();
            }

            @Override
            public void onItemToggled(FileItem item, boolean sel) {
                String key = item.getUri().toString();
                if (sel) selected.put(key, item);
                else selected.remove(key);
            }

            @Override
            public void onHeaderClicked(String h) {
                viewModel.toggleHeader(h);
            }
        });

        fileAdapter.setOnFolderClickListener(f -> {
            state.directory = new File(f.getPath());
            viewModel.setCategory("FILES", state.directory.getAbsolutePath());
        });

        lm.setSpanSizeLookup(fileAdapter.getSpanSizeLookup(3));
        binding.rvFiles.setLayoutManager(lm);
        binding.rvFiles.setAdapter(fileAdapter);
    }

    private void updateState(Category cat, boolean explorer, String sub, File dir) {
        state.category = cat;
        state.explorer = explorer;

        if (sub == null) {
            if (cat == Category.APPS) state.subFilter = "Installed";
            else if (cat == Category.FILES) state.subFilter = "All";
            else state.subFilter = "Date";
        } else {
            state.subFilter = sub;
        }

        if (dir != null) state.directory = dir;
        renderState();
    }

    private void renderState() {
        binding.layoutDashboard.setVisibility(state.explorer ? View.GONE : View.VISIBLE);
        binding.layoutExplorer.setVisibility(state.explorer ? View.VISIBLE : View.GONE);

        int primary = ContextCompat.getColor(this, R.color.primary);
        for (TextView t : tabViews) t.setTextColor(Color.GRAY);
        if (state.category == Category.IMAGES) binding.tabPhotos.setTextColor(primary);
        else if (state.category == Category.VIDEOS) binding.tabVideos.setTextColor(primary);
        else if (state.category == Category.APPS) binding.tabApps.setTextColor(primary);
        else if (state.category == Category.MUSIC) binding.tabMusic.setTextColor(primary);
        else binding.tabFiles.setTextColor(primary);

        setupFilters();
        viewModel.setCategory(state.category.name(), state.subFilter);
    }

    private void setupFilters() {
        List<String> filters;
        if (state.category == Category.APPS) filters = Arrays.asList("Installed", "Not Installed");
        else if (state.category == Category.FILES) filters = Collections.emptyList();
        else filters = Arrays.asList("Date", "Folders");

        binding.subFilterScroll.setVisibility(filters.isEmpty() ? View.GONE : View.VISIBLE);
        binding.toggleGroupFilters.removeAllViews();

        for (String f : filters) {
            MaterialButton btn = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(f);
            btn.setCheckable(true);
            btn.setPadding(32, 0, 32, 0);
            btn.setChecked(f.equalsIgnoreCase(state.subFilter));
            btn.setOnClickListener(v -> updateState(state.category, true, f, null));
            binding.toggleGroupFilters.addView(btn);
        }
    }

    private void setupTabs() {
        binding.tabFiles.setOnClickListener(v -> updateState(Category.FILES, false, "All", null));
        binding.tabVideos.setOnClickListener(v -> updateState(Category.VIDEOS, true, "Date", null));
        binding.tabApps.setOnClickListener(v -> updateState(Category.APPS, true, "Installed", null));
        binding.tabPhotos.setOnClickListener(v -> updateState(Category.IMAGES, true, "Date", null));
        binding.tabMusic.setOnClickListener(v -> updateState(Category.MUSIC, true, "Date", null));
    }

    private void setupDashboard() {
        binding.cardInternalStorage.setOnClickListener(v -> updateState(Category.FILES, true, "All", null));
    }

    private void updateSelection() {
        int count = selected.size();
        binding.tvSelectedCount.setText(count + " selected");
        binding.btnSendSelected.setEnabled(count > 0);
    }

    private void setupBottomBar() {
        binding.btnSendSelected.setOnClickListener(v -> {
            Intent intent = new Intent(this, ServiceActivity.class);
            intent.putExtra("isSendMode", true);
            startActivity(intent);
        });

        binding.btnViewSelected.setOnClickListener(v -> {
            if (selected.isEmpty()) return;

            BottomSheetDialog dialog = new BottomSheetDialog(this);
            LayoutSelectedFilesSheetBinding sheetBinding = LayoutSelectedFilesSheetBinding.inflate(getLayoutInflater());
            dialog.setContentView(sheetBinding.getRoot());

            SelectedFilesAdapter adapter = new SelectedFilesAdapter(item -> {
                selected.remove(item.getUri().toString());
                updateSelection();
                fileAdapter.setSelectedPaths(selected.keySet());
                if (selected.isEmpty()) dialog.dismiss();
            });

            sheetBinding.rvSelectedFiles.setLayoutManager(new LinearLayoutManager(this));
            sheetBinding.rvSelectedFiles.setAdapter(adapter);
            adapter.setFiles(new ArrayList<>(selected.values()));

            sheetBinding.btnClearAll.setOnClickListener(v1 -> {
                selected.clear();
                updateSelection();
                fileAdapter.setSelectedPaths(selected.keySet());
                dialog.dismiss();
            });

            dialog.show();
        });
    }

    private void handleBack() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (state.explorer && state.category == Category.FILES && !state.directory.equals(Environment.getExternalStorageDirectory())) {
                    state.directory = state.directory.getParentFile();
                    viewModel.setCategory("FILES", state.directory.getAbsolutePath());
                } else if (state.explorer) {
                    updateState(Category.FILES, false, "All", null);
                } else {
                    setEnabled(false);
                    onBackPressed();
                }
            }
        });
    }
}