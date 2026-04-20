package com.ultrafast.transfer.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "transfer_history")
public class TransferRecord {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String deviceName;
    public String type; // "SEND" or "RECEIVE"
    public String fileName;
    public long fileSize;
    public long timestamp; // Date in milliseconds
    public String status; // "COMPLETED" or "FAILED"
}