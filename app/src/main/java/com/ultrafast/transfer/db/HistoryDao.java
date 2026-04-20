package com.ultrafast.transfer.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface HistoryDao {
    @Insert
    void insert(TransferRecord record);

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    List<TransferRecord> getAllHistory();

    @Query("DELETE FROM transfer_history WHERE id = :id")
    void delete(int id);

   @Query("DELETE FROM transfer_history WHERE id = :id")
    void DeleteBySession(int id);
}