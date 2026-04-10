package com.ultrafast.transfer;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.ultrafast.transfer.databinding.ActivityFileBrowserBinding;
import com.ultrafast.transfer.databinding.LayoutSelectedFilesSheetBinding;

import java.io.File;
import java.io.FileFilter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FileBrowserActivity extends AppCompatActivity {

    private static final String TAG = "FileBrowserActivity";
    private ActivityFileBrowserBinding binding;
    private FileBrowserAdapter fileAdapter;
    private FileLoader fileLoader;
    private final List<FileItem> allFilesList = new ArrayList<>();
    private final Map<String, FileItem> selectedFileItems = new HashMap<>();
    private final HashSet<String> collapsedFolders = new HashSet<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    enum Category { FILES, VIDEOS, APPS, IMAGES, MUSIC }
    private Category currentCategory = Category.FILES;

    private String currentSubFilter = "All";
    private final AtomicInteger currentLoadToken = new AtomicInteger(0);
    
    private File currentDirectory;
    private boolean isExplorerMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFileBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String categoryExtra = getIntent().getStringExtra("category");
        if (categoryExtra != null) {
            try {
                currentCategory = Category.valueOf(categoryExtra);
            } catch (IllegalArgumentException e) {
                currentCategory = Category.FILES;
            }
        }

        fileLoader = new FileLoader(this);
        currentDirectory = Environment.getExternalStorageDirectory();

        setupRecyclerView();
        setupCategoryTabs();
        setupDashboard();
        setupBottomBar();

        setInitialTabSelection();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isExplorerMode && currentDirectory != null && !currentDirectory.equals(Environment.getExternalStorageDirectory())) {
                    currentDirectory = currentDirectory.getParentFile();
                    refreshFiles();
                } else if (isExplorerMode) {
                    switchToDashboard();
                } else {
                    setEnabled(false);
                    onBackPressed();
                }
            }
        });
    }

    private void setupDashboard() {
        binding.dashApps.setOnClickListener(v -> switchTab(Category.APPS));
        binding.dashImages.setOnClickListener(v -> switchTab(Category.IMAGES));
        binding.dashVideos.setOnClickListener(v -> switchTab(Category.VIDEOS));
        binding.dashMusic.setOnClickListener(v -> switchTab(Category.MUSIC));
        
        binding.dashDocs.setOnClickListener(v -> {
            isExplorerMode = true;
            currentCategory = Category.FILES;
            currentSubFilter = "Docs";
            updateUIForMode();
            refreshFiles();
        });
        
        binding.dashDownloads.setOnClickListener(v -> {
            isExplorerMode = true;
            currentDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            updateUIForMode();
            refreshFiles();
        });

        binding.dashArchives.setOnClickListener(v -> {
            isExplorerMode = true;
            currentCategory = Category.FILES;
            currentSubFilter = "Archives";
            updateUIForMode();
            refreshFiles();
        });

        binding.cardInternalStorage.setOnClickListener(v -> {
            isExplorerMode = true;
            currentDirectory = Environment.getExternalStorageDirectory();
            updateUIForMode();
            refreshFiles();
        });
    }

    private void switchTab(Category category) {
        currentCategory = category;
        isExplorerMode = (category != Category.FILES);
        
        TextView targetTab;
        switch (category) {
            case VIDEOS: targetTab = binding.tabVideos; break;
            case APPS: targetTab = binding.tabApps; break;
            case IMAGES: targetTab = binding.tabPhotos; break;
            case MUSIC: targetTab = binding.tabMusic; break;
            default: targetTab = binding.tabFiles;
        }
        
        updateTabSelection(targetTab);
        updateUIForMode();
        
        if (isExplorerMode) {
            currentSubFilter = (category == Category.APPS) ? "Installed" : "Date";
            refreshSubFilters();
        }
    }

    private void switchToDashboard() {
        isExplorerMode = false;
        currentCategory = Category.FILES;
        updateTabSelection(binding.tabFiles);
        updateUIForMode();
    }

    private void updateUIForMode() {
        if (isExplorerMode) {
            binding.layoutDashboard.setVisibility(View.GONE);
            binding.layoutExplorer.setVisibility(View.VISIBLE);
            binding.subFilterScroll.setVisibility(currentCategory == Category.FILES ? View.GONE : View.VISIBLE);
            
            if (currentCategory == Category.FILES) {
                binding.tvCurrentPath.setVisibility(View.VISIBLE);
                binding.tvCurrentPath.setText(currentDirectory.getAbsolutePath());
            } else {
                binding.tvCurrentPath.setVisibility(View.GONE);
            }
            
            GridLayoutManager lm = (GridLayoutManager) binding.rvFiles.getLayoutManager();
            if (currentCategory == Category.FILES) {
                lm.setSpanCount(1); // List view for folder explorer
            } else {
                lm.setSpanCount(3); // Grid view for media
            }
        } else {
            binding.layoutDashboard.setVisibility(View.VISIBLE);
            binding.layoutExplorer.setVisibility(View.GONE);
            binding.subFilterScroll.setVisibility(View.GONE);
            binding.tvCurrentPath.setVisibility(View.GONE);
        }
    }

    private void setInitialTabSelection() {
        if (currentCategory == Category.FILES) {
            switchToDashboard();
        } else {
            switchTab(currentCategory);
        }
    }

    private void setupRecyclerView() {
        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        fileAdapter = new FileBrowserAdapter(new FileBrowserAdapter.OnSelectionChangedListener() {
            @Override
            public void onSelectionChanged() {
                updateSelectionCount();
            }

            @Override
            public void onItemToggled(FileItem item, boolean isSelected) {
                if (isSelected) {
                    selectedFileItems.put(item.getPath(), item);
                } else {
                    selectedFileItems.remove(item.getPath());
                }
            }

            @Override
            public void onHeaderClicked(String headerTitle) {
                if (collapsedFolders.contains(headerTitle)) {
                    collapsedFolders.remove(headerTitle);
                } else {
                    collapsedFolders.add(headerTitle);
                }
                applyGrouping();
            }
        });
        
        fileAdapter.setOnFolderClickListener(folder -> {
            currentDirectory = new File(folder.getPath());
            binding.tvCurrentPath.setText(currentDirectory.getAbsolutePath());
            refreshFiles();
        });

        layoutManager.setSpanSizeLookup(fileAdapter.getSpanSizeLookup(3));
        binding.rvFiles.setLayoutManager(layoutManager);
        binding.rvFiles.setItemAnimator(null);
        binding.rvFiles.setAdapter(fileAdapter);
    }

    private void refreshSubFilters() {
        binding.toggleGroupFilters.removeAllViews();
        binding.toggleGroupFilters.clearOnButtonCheckedListeners();
        
        List<String> filters = new ArrayList<>();
        if (currentCategory == Category.APPS) {
            filters.add("Installed");
            filters.add("Not Installed");
        } else if (currentCategory == Category.IMAGES || currentCategory == Category.VIDEOS || currentCategory == Category.MUSIC) {
            filters.add("Date");
            filters.add("Folders");
        }

        if (filters.isEmpty()) {
            binding.subFilterScroll.setVisibility(View.GONE);
            refreshFiles();
            return;
        }

        binding.subFilterScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < filters.size(); i++) {
            String filter = filters.get(i);
            MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(filter);
            button.setId(View.generateViewId());
            button.setTag(filter);
            button.setAllCaps(false);
            button.setPadding(24, 0, 24, 0);
            button.setGravity(Gravity.CENTER);
            
            binding.toggleGroupFilters.addView(button);
            
            if (filter.equals(currentSubFilter)) {
                binding.toggleGroupFilters.check(button.getId());
            } else if (i == 0 && (currentSubFilter.equals("All") || !filters.contains(currentSubFilter))) {
                binding.toggleGroupFilters.check(button.getId());
                currentSubFilter = filter;
            }
        }

        binding.toggleGroupFilters.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                View checkedButton = group.findViewById(checkedId);
                if (checkedButton != null) {
                    currentSubFilter = (String) checkedButton.getTag();
                    refreshFiles();
                }
            }
        });
        
        refreshFiles();
    }

    private void refreshFiles() {
        if (!isExplorerMode && currentCategory == Category.FILES) return;

        synchronized (allFilesList) {
            allFilesList.clear();
        }
        fileAdapter.submitList(new ArrayList<>());
        final int token = currentLoadToken.incrementAndGet();

        FileLoader.FileLoadCallback callback = new FileLoader.FileLoadCallback() {
            @Override
            public void onLoading() {
                if (token == currentLoadToken.get()) {
                    mainHandler.post(() -> binding.progressBar.setVisibility(View.VISIBLE));
                }
            }

            @Override
            public void onChunkLoaded(List<FileItem> chunk) { 
                if (token == currentLoadToken.get()) {
                    synchronized (allFilesList) {
                        allFilesList.addAll(chunk);
                    }
                    applyGrouping();
                }
            }

            @Override
            public void onComplete(List<FileItem> allFiles) {
                if (token == currentLoadToken.get()) {
                    mainHandler.post(() -> binding.progressBar.setVisibility(View.GONE));
                }
            }

            @Override
            public void onError(Exception e) {
                if (token == currentLoadToken.get()) {
                    mainHandler.post(() -> {
                        binding.progressBar.setVisibility(View.GONE);
                        Log.e(TAG, "Error loading files", e);
                    });
                }
            }
        };

        if (currentCategory == Category.APPS) {
            if ("Not Installed".equals(currentSubFilter)) {
                fileLoader.scanMediaStore("APPS", callback);
            } else {
                fileLoader.loadInstalledApps(callback);
            }
        } else if (currentCategory == Category.IMAGES || currentCategory == Category.VIDEOS || currentCategory == Category.MUSIC) {
            fileLoader.scanMediaStore(currentCategory.name(), callback);
        } else {
            // Folder Explorer or specific filters like Docs/Archives
            if ("Docs".equals(currentSubFilter)) {
                fileLoader.scanMediaStore("DOCS", callback);
            } else if ("Archives".equals(currentSubFilter)) {
                fileLoader.scanMediaStore("ARCHIVES", callback);
            } else {
                fileLoader.loadFiles(currentDirectory, getFolderExplorerFilter(), callback);
            }
        }
    }

    private void applyGrouping() {
        final List<FileItem> snapshot;
        synchronized (allFilesList) {
            snapshot = new ArrayList<>(allFilesList);
        }

        executorService.execute(() -> {
            List<BrowserItem> browserItems = new ArrayList<>();

            if (isExplorerMode && currentCategory == Category.FILES && currentSubFilter.equals("All")) {
                // Folder Explorer view: Folders then Files
                List<FileItem> folders = new ArrayList<>();
                List<FileItem> files = new ArrayList<>();
                for (FileItem item : snapshot) {
                    if (item.isDirectory()) folders.add(item);
                    else files.add(item);
                }
                Collections.sort(folders, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                
                for (FileItem f : folders) browserItems.add(BrowserItem.file(f));
                for (FileItem f : files) browserItems.add(BrowserItem.file(f));
                
            } else if (currentSubFilter.equals("Date") || currentSubFilter.equals("Folders")) {
                Map<String, List<FileItem>> grouped = (currentSubFilter.equals("Date"))
                        ? groupByDate(snapshot)
                        : groupByFolder(snapshot);

                for (Map.Entry<String, List<FileItem>> entry : grouped.entrySet()) {
                    String groupName = entry.getKey();
                    boolean isCollapsed = collapsedFolders.contains(groupName);
                    browserItems.add(BrowserItem.header(groupName, isCollapsed));
                    if (!isCollapsed) {
                        for (FileItem item : entry.getValue()) {
                            browserItems.add(BrowserItem.file(item));
                        }
                    }
                }
            } else {
                for (FileItem item : snapshot) {
                    browserItems.add(BrowserItem.file(item));
                }
            }

            mainHandler.post(() -> {
                fileAdapter.updateSelection(selectedFileItems.keySet());
                fileAdapter.submitList(browserItems);
            });
        });
    }

    private Map<String, List<FileItem>> groupByDate(List<FileItem> files) {
        Map<String, List<FileItem>> map = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        for (FileItem item : files) {
            String date = sdf.format(new Date(item.getLastModified()));
            if (!map.containsKey(date)) map.put(date, new ArrayList<>());
            map.get(date).add(item);
        }
        return map;
    }

    private Map<String, List<FileItem>> groupByFolder(List<FileItem> files) {
        Map<String, List<FileItem>> map = new TreeMap<>();
        for (FileItem item : files) {
            String folder = item.getFolderName();
            if (!map.containsKey(folder)) map.put(folder, new ArrayList<>());
            map.get(folder).add(item);
        }
        return map;
    }

    private void updateSelectionCount() {
        int count = selectedFileItems.size();
        binding.tvSelectedCount.setText(count + " File(s) selected");
        binding.btnSendSelected.setEnabled(count > 0);
    }

    private void setupCategoryTabs() {
        binding.tabFiles.setOnClickListener(v -> switchTab(Category.FILES));
        binding.tabVideos.setOnClickListener(v -> switchTab(Category.VIDEOS));
        binding.tabApps.setOnClickListener(v -> switchTab(Category.APPS));
        binding.tabPhotos.setOnClickListener(v -> switchTab(Category.IMAGES));
        binding.tabMusic.setOnClickListener(v -> switchTab(Category.MUSIC));
    }

    private void setupBottomBar() {
        binding.btnSendSelected.setOnClickListener(v -> {
            if (!selectedFileItems.isEmpty()) {
                Intent intent = new Intent(this, DiscoveryActivity.class);
                intent.putExtra("isSendMode", true);
                ArrayList<FileItem> items = new ArrayList<>(selectedFileItems.values());
                intent.putParcelableArrayListExtra("selectedFiles", items);
                startActivity(intent);
            }
        });
        binding.btnBack.setOnClickListener(v -> {
            if (isExplorerMode && currentDirectory != null && !currentDirectory.equals(Environment.getExternalStorageDirectory())) {
                currentDirectory = currentDirectory.getParentFile();
                refreshFiles();
            } else if (isExplorerMode) {
                switchToDashboard();
            } else {
                finish();
            }
        });
        binding.btnViewSelected.setOnClickListener(v -> showSelectedFilesSheet());
    }

    private void showSelectedFilesSheet() {
        if (selectedFileItems.isEmpty()) return;

        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        LayoutSelectedFilesSheetBinding sheetBinding = LayoutSelectedFilesSheetBinding.inflate(getLayoutInflater());
        bottomSheet.setContentView(sheetBinding.getRoot());

        SelectedFilesAdapter adapter = new SelectedFilesAdapter(item -> {
            selectedFileItems.remove(item.getPath());
            fileAdapter.updateSelection(selectedFileItems.keySet());
            updateSelectionCount();
            if (selectedFileItems.isEmpty()) bottomSheet.dismiss();
        });

        sheetBinding.rvSelectedFiles.setLayoutManager(new LinearLayoutManager(this));
        sheetBinding.rvSelectedFiles.setAdapter(adapter);
        adapter.setFiles(new ArrayList<>(selectedFileItems.values()));

        sheetBinding.btnClearAll.setOnClickListener(v -> {
            selectedFileItems.clear();
            fileAdapter.updateSelection(new HashSet<>());
            updateSelectionCount();
            bottomSheet.dismiss();
        });

        bottomSheet.show();
    }

    private void updateTabSelection(TextView selected) {
        int selectedColor = Color.parseColor("#2196F3");
        int unselectedColor = Color.parseColor("#757575");
        binding.tabFiles.setTextColor(unselectedColor);
        binding.tabVideos.setTextColor(unselectedColor);
        binding.tabApps.setTextColor(unselectedColor);
        binding.tabPhotos.setTextColor(unselectedColor);
        binding.tabMusic.setTextColor(unselectedColor);
        selected.setTextColor(selectedColor);
    }

    private FileFilter getFolderExplorerFilter() {
        return file -> !file.isHidden();
    }
}