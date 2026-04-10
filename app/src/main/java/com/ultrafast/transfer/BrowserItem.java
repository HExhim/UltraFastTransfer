package com.ultrafast.transfer;

import java.util.Objects;

public class BrowserItem {
    public static final int TYPE_HEADER = 0;
    public static final int TYPE_FILE_MEDIA = 1;
    public static final int TYPE_FILE_STANDARD = 2;
    public static final int TYPE_FILE_MUSIC = 3;

    private final int type;
    private final FileItem fileItem;
    private final String headerTitle;
    private final boolean isCollapsed;

    private BrowserItem(int type, FileItem fileItem, String headerTitle, boolean isCollapsed) {
        this.type = type;
        this.fileItem = fileItem;
        this.headerTitle = headerTitle;
        this.isCollapsed = isCollapsed;
    }

    public static BrowserItem header(String title) {
        return new BrowserItem(TYPE_HEADER, null, title, false);
    }

    public static BrowserItem header(String title, boolean isCollapsed) {
        return new BrowserItem(TYPE_HEADER, null, title, isCollapsed);
    }

    public static BrowserItem file(FileItem item) {
        int viewType;
        if (item.isAudio()) {
            viewType = TYPE_FILE_MUSIC;
        } else if (item.isImage() || item.isVideo()) {
            viewType = TYPE_FILE_MEDIA;
        } else {
            viewType = TYPE_FILE_STANDARD;
        }
        return new BrowserItem(viewType, item, null, false);
    }

    public int getType() { return type; }
    public FileItem getFileItem() { return fileItem; }
    public String getHeaderTitle() { return headerTitle; }
    public boolean isCollapsed() { return isCollapsed; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrowserItem that = (BrowserItem) o;
        return type == that.type &&
                isCollapsed == that.isCollapsed &&
                Objects.equals(fileItem, that.fileItem) &&
                Objects.equals(headerTitle, that.headerTitle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, fileItem, headerTitle, isCollapsed);
    }
}