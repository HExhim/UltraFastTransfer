package com.ultrafast.transfer;

import android.app.Application;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ultrafast.transfer.data.FileRepository;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileBrowserViewModel extends AndroidViewModel {
    private final FileRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<BrowserItem>> activeItems = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private String currentCacheKey = "";

    private final List<BrowserItem> rawItems = new ArrayList<>();
    private final Set<String> collapsedHeaders = new HashSet<>();
    private final Object lock = new Object();

    public FileBrowserViewModel(@NonNull Application application) {
        super(application);
        repository = new FileRepository(application);
    }

    public LiveData<List<BrowserItem>> getActiveItems() {
        return activeItems;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    public void setCategory(String category, String subFilter) {
        String cacheKey = category + "_" + (subFilter != null ? subFilter : "All");

        if (cacheKey.equals(currentCacheKey) && activeItems.getValue() != null && !activeItems.getValue().isEmpty())
            return;

        currentCacheKey = cacheKey;
        synchronized (lock) {
            collapsedHeaders.clear();
            rawItems.clear();
        }
        activeItems.setValue(new ArrayList<>());
        loadData(category, subFilter);
    }

    private void loadData(String category, String subFilter) {
        isLoading.postValue(true);
        executor.execute(() -> {
            List<FileItem> files = repository.getFilesByCategory(category, subFilter);
            List<BrowserItem> result = new ArrayList<>();

            if (files == null || files.isEmpty()) {
                synchronized (lock) {
                    rawItems.clear();
                    activeItems.postValue(new ArrayList<>());
                }
                isLoading.postValue(false);
                return;
            }

            if ("Folders".equalsIgnoreCase(subFilter)) {
                Map<String, List<FileItem>> groups = new LinkedHashMap<>();
                for (FileItem f : files) {
                    String folder = f.getFolderName() != null ? f.getFolderName() : "Others";
                    if (!groups.containsKey(folder)) groups.put(folder, new ArrayList<>());
                    groups.get(folder).add(f);
                }
                for (Map.Entry<String, List<FileItem>> entry : groups.entrySet()) {
                    result.add(BrowserItem.header(entry.getKey(), "(" + entry.getValue().size() + ")"));
                    for (FileItem f : entry.getValue()) result.add(BrowserItem.file(f));
                }
            } else if ("Date".equalsIgnoreCase(subFilter)) {
                Map<String, List<FileItem>> dateGroups = new LinkedHashMap<>();
                for (FileItem f : files) {
                    String label = getDateLabel(f.getLastModified());
                    if (!dateGroups.containsKey(label)) dateGroups.put(label, new ArrayList<>());
                    dateGroups.get(label).add(f);
                }
                for (Map.Entry<String, List<FileItem>> entry : dateGroups.entrySet()) {
                    result.add(BrowserItem.header(entry.getKey(), "(" + entry.getValue().size() + ")"));
                    for (FileItem f : entry.getValue()) result.add(BrowserItem.file(f));
                }
            } else {
                for (FileItem f : files) result.add(BrowserItem.file(f));
            }

            synchronized (lock) {
                rawItems.clear();
                rawItems.addAll(result);
                postFilteredItems();
            }
            isLoading.postValue(false);
        });
    }

    public void toggleHeader(String title) {
        synchronized (lock) {
            if (collapsedHeaders.contains(title)) {
                collapsedHeaders.remove(title);
            } else {
                collapsedHeaders.add(title);
            }
            postFilteredItems();
        }
    }

    private void postFilteredItems() {
        List<BrowserItem> filtered = new ArrayList<>();
        boolean currentGroupCollapsed = false;
        synchronized (lock) {
            for (BrowserItem item : rawItems) {
                if (item.getType() == BrowserItem.TYPE_HEADER) {
                    boolean collapsed = collapsedHeaders.contains(item.getHeaderTitle());
                    // Create a new instance so ListAdapter/DiffUtil detects the change
                    BrowserItem headerItem = BrowserItem.header(item.getHeaderTitle(), item.getHeaderCount());
                    headerItem.setCollapsed(collapsed);
                    filtered.add(headerItem);
                    currentGroupCollapsed = collapsed;
                } else if (!currentGroupCollapsed) {
                    filtered.add(item);
                }
            }
        }
        activeItems.postValue(filtered);
    }

    private String getDateLabel(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        
        Calendar now = Calendar.getInstance();
        
        if (DateUtils.isToday(time)) {
            return "Today";
        }
        
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) && 
            now.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }

        now = Calendar.getInstance();
        if (now.get(Calendar.YEAR) == calendar.get(Calendar.YEAR)) {
            return DateUtils.formatDateTime(getApplication(), time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
        } else {
            return DateUtils.formatDateTime(getApplication(), time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
    }
}