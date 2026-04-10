package com.ultrafast.transfer;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class FileItem implements Parcelable {

    private final Uri uri;
    private final String name;
    private final long size;
    private final long lastModified;
    private final String extension;
    private final boolean directory;
    private final String path; // Original path if available, or uri string

    private boolean isSystem;
    private String folderName = "Unknown";
    private String artist;
    private String album;

    private static final List<String> IMAGE_EXT = Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif");
    private static final List<String> VIDEO_EXT = Arrays.asList("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "mpg", "mpeg");
    private static final List<String> AUDIO_EXT = Arrays.asList("mp3", "wav", "flac", "m4a", "ogg", "aac", "wma", "amr");

    public FileItem(@NonNull Uri uri, String name, long size, long lastModified,
                    String extension, boolean directory, String path) {
        this.uri = uri;
        this.name = name != null ? name : "Unknown";
        this.size = size;
        this.lastModified = lastModified > 0 ? lastModified : System.currentTimeMillis();
        this.extension = extension != null ? extension.toLowerCase() : "";
        this.directory = directory;
        this.path = path != null ? path : uri.toString();

        extractFolderName();
    }

    private void extractFolderName() {
        if (path != null) {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null) {
                folderName = parent.getName();
            }
        }
        
        if (folderName == null || folderName.equals("Unknown")) {
            // Try extracting from URI path
            String uriPath = uri.getPath();
            if (uriPath != null) {
                int lastSlash = uriPath.lastIndexOf('/');
                if (lastSlash > 0) {
                    int prevSlash = uriPath.lastIndexOf('/', lastSlash - 1);
                    if (prevSlash >= 0) {
                        folderName = uriPath.substring(prevSlash + 1, lastSlash);
                    }
                }
            }
        }

        if (folderName == null || folderName.trim().isEmpty()) {
            folderName = "Unknown";
        }
    }

    public static FileItem fromFile(File file) {
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        String ext = dotIndex > 0 ? name.substring(dotIndex + 1).toLowerCase() : "";

        return new FileItem(
                Uri.fromFile(file),
                name,
                file.length(),
                file.lastModified(),
                ext,
                file.isDirectory(),
                file.getAbsolutePath()
        );
    }

    public Uri getUri() {
        return uri;
    }

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

    public boolean isDirectory() {
        return directory;
    }

    public String getFolderName() {
        return folderName != null ? folderName : "Unknown";
    }

    public boolean isImage() {
        return IMAGE_EXT.contains(extension);
    }

    public boolean isVideo() {
        return VIDEO_EXT.contains(extension);
    }

    public boolean isAudio() {
        return AUDIO_EXT.contains(extension);
    }

    public boolean isApk() {
        return "apk".equals(extension);
    }

    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean system) {
        this.isSystem = system;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    protected FileItem(Parcel in) {
        uri = in.readParcelable(Uri.class.getClassLoader());
        name = in.readString();
        size = in.readLong();
        lastModified = in.readLong();
        extension = in.readString();
        directory = in.readByte() != 0;
        path = in.readString();
        isSystem = in.readByte() != 0;
        folderName = in.readString();
        artist = in.readString();
        album = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(uri, flags);
        dest.writeString(name);
        dest.writeLong(size);
        dest.writeLong(lastModified);
        dest.writeString(extension);
        dest.writeByte((byte) (directory ? 1 : 0));
        dest.writeString(path);
        dest.writeByte((byte) (isSystem ? 1 : 0));
        dest.writeString(folderName);
        dest.writeString(artist);
        dest.writeString(album);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<FileItem> CREATOR = new Creator<FileItem>() {
        @Override
        public FileItem createFromParcel(Parcel in) {
            return new FileItem(in);
        }

        @Override
        public FileItem[] newArray(int size) {
            return new FileItem[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileItem fileItem = (FileItem) o;
        return Objects.equals(uri, fileItem.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }
}