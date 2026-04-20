package com.ultrafast.transfer.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Objects;

@Entity(
        tableName = "files",
        indices = {
                @Index("path"),
                @Index("category"),
                @Index("lastModified"),
                @Index("folderName")
        }
)
public class FileEntity {

    @PrimaryKey
    @NonNull
    private String path;

    private String name;
    private long size;
    private long lastModified;
    private String extension;
    private String category;
    private boolean directory;
    private String folderName;

    public FileEntity(
            @NonNull String path,
            String name,
            long size,
            long lastModified,
            String extension,
            String category,
            boolean directory,
            String folderName
    ) {
        this.path = path;
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
        this.extension = extension;
        this.category = category;
        this.directory = directory;
        this.folderName = folderName;
    }

    @NonNull
    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public String getExtension() {
        return extension;
    }

    public String getCategory() {
        return category;
    }

    public boolean isDirectory() {
        return directory;
    }

    public String getFolderName() {
        return folderName;
    }

    // 🔥 IMPORTANT FOR DIFFING (VERY IMPORTANT FOR PAGING SMOOTHNESS)
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileEntity)) return false;
        FileEntity that = (FileEntity) o;
        return path.equals(that.path) &&
                lastModified == that.lastModified &&
                size == that.size;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, lastModified, size);
    }
}