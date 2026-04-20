package com.ultrafast.transfer.db;

import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<FileEntity> files);

    @Query("""
        SELECT * FROM files
        WHERE category = :category
        ORDER BY lastModified DESC
    """)
    PagingSource<Integer, FileEntity> getFilesByCategory(String category);

    @Query("""
        SELECT * FROM files
        WHERE folderName = :folderName
        ORDER BY lastModified DESC
    """)
    PagingSource<Integer, FileEntity> getFilesByFolder(String folderName);

    @Query("DELETE FROM files WHERE category = :category")
    void deleteByCategory(String category);

    @Query("SELECT COUNT(*) FROM files WHERE category = :category")
    int getCountByCategory(String category);

    @Query("""
        SELECT lastModified FROM files
        WHERE category = :category
        ORDER BY lastModified DESC
        LIMIT 1
    """)
    Long getLastModifiedByCategory(String category);
}