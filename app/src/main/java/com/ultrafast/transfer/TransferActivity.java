package com.ultrafast.transfer;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.ultrafast.transfer.network.transfer.ParallelFileServer;
import com.ultrafast.transfer.network.transfer.TransferEngine;

import java.util.ArrayList;
import java.util.List;

public class TransferActivity extends AppCompatActivity {

    private static final String TAG = "TransferActivity";
    private static final int FILE_PICKER_REQUEST_CODE = 201;

    private String deviceName;
    private String deviceIp;
    private boolean isSendMode;

    private TextView tvDeviceName, tvSpeed, tvProgressPercent, tvProgressFiles;
    private LinearProgressIndicator pbOverall;
    private RecyclerView rvFiles;
    private FileTransferAdapter adapter;
    private final List<TransferFile> transferFiles = new ArrayList<>();

    private final TransferEngine engine = new TransferEngine();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        deviceName = getIntent().getStringExtra("deviceName");
        deviceIp = getIntent().getStringExtra("deviceIp");
        isSendMode = getIntent().getBooleanExtra("isSendMode", true);

        initViews();

        if (isSendMode) {
            setupSender();
        } else {
            setupReceiver();
        }
    }

    private void setupSender() {
        if (deviceIp == null) return;

        // 1. Send Handshake
        engine.sendHandshake(deviceIp, Build.MODEL);

        // 2. Prepare files from Intent
        ArrayList<FileItem> selectedFiles = getIntent().getParcelableArrayListExtra("selectedFiles");
        List<ParallelFileServer.ManifestFile> manifest = new ArrayList<>();

        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (FileItem item : selectedFiles) {
                TransferFile file = new TransferFile(item.getName(), item.getSize(), item.getUri());
                transferFiles.add(file);
                manifest.add(new ParallelFileServer.ManifestFile(file.name, file.size));
            }
            adapter.notifyDataSetChanged();
            updateOverallProgress();

            // 3. Send Manifest to Receiver
            engine.sendManifest(deviceIp, manifest);

            // 4. Start Transfer for each file
            for (TransferFile file : transferFiles) {
                startTransfer(file);
            }
        }
    }

    private void setupReceiver() {
        ParallelFileServer.getInstance().setConnectionListener((remoteName, remoteIp) -> {
            runOnUiThread(() -> {
                this.deviceName = remoteName;
                this.deviceIp = remoteIp;
                tvDeviceName.setText(deviceName);
            });
        });

        ParallelFileServer.getInstance().startServer(this, new ParallelFileServer.ServerCallback() {
            @Override
            public void onHandshake(String deviceName, String ip) {
                // This is the missing method.
                // It triggers when the sender identifies themselves.
                Log.d("TransferActivity", "Handshake from: " + deviceName + " at " + ip);
            }

            @Override
            public void onManifestReceived(List<ParallelFileServer.ManifestFile> files) {
                runOnUiThread(() -> {
                    transferFiles.clear();
                    for (ParallelFileServer.ManifestFile f : files) {
                        transferFiles.add(new TransferFile(f.name, f.size, null));
                    }
                    adapter.notifyDataSetChanged();
                    updateOverallProgress();
                });
            }

            @Override
            public void onFileReceived(String fileName, long size) {
                runOnUiThread(() -> {
                    TransferFile file = findFileByName(fileName);
                    if (file == null) {
                        file = new TransferFile(fileName, size, null);
                        transferFiles.add(file);
                        adapter.notifyItemInserted(transferFiles.size() - 1);
                    }
                    file.status = "Receiving...";
                    adapter.notifyItemChanged(transferFiles.indexOf(file));
                });
            }

            @Override
            public void onProgress(String fileName, long current, long total) {
                runOnUiThread(() -> {
                    TransferFile file = findFileByName(fileName);
                    if (file != null) {
                        file.progress = (int) (current * 100 / total);
                        adapter.notifyItemChanged(transferFiles.indexOf(file));
                        updateOverallProgress();
                    }
                });
            }

            @Override
            public void onTransferComplete(String fileName) {
                runOnUiThread(() -> {
                    TransferFile file = findFileByName(fileName);
                    if (file != null) {
                        file.status = "Completed";
                        file.progress = 100;
                        adapter.notifyItemChanged(transferFiles.indexOf(file));
                        updateOverallProgress();
                    }
                });
            }

            @Override
            public void onTransferFailed(String fileName, String error) {
                runOnUiThread(() -> Toast.makeText(TransferActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onServerStarted() {
                Log.d(TAG, "Server started and ready.");
            }

            @Override
            public void onError(String fileName, String error) {
                Log.e("TransferError", fileName + " failed: " + error);
            }
        });
    }

    private void startTransfer(TransferFile file) {
        engine.sendFile(this, file.uri, file.name, file.size, deviceIp, new TransferEngine.ProgressListener() {
            @Override
            public void onProgress(long current, long total) {
                runOnUiThread(() -> {
                    file.progress = (int) (current * 100 / total);
                    file.status = "Sending... " + file.progress + "%";
                    adapter.notifyItemChanged(transferFiles.indexOf(file));
                    updateOverallProgress();
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    file.status = "Completed";
                    file.progress = 100;
                    adapter.notifyItemChanged(transferFiles.indexOf(file));
                    updateOverallProgress();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    file.status = "Failed";
                    adapter.notifyItemChanged(transferFiles.indexOf(file));
                });
            }
        });
    }

    private void initViews() {
        tvDeviceName = findViewById(R.id.tvTitle);
        tvSpeed = findViewById(R.id.tvProgressETA);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        tvProgressFiles = findViewById(R.id.tvProgressFiles);
        pbOverall = findViewById(R.id.pbOverall);
        rvFiles = findViewById(R.id.rvFiles);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

        if (deviceName != null) tvDeviceName.setText(deviceName);

        adapter = new FileTransferAdapter(transferFiles);
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        rvFiles.setAdapter(adapter);

        findViewById(R.id.btnSendFiles).setOnClickListener(v -> openFilePicker());
        updateOverallProgress();
    }

    private void updateOverallProgress() {
        if (transferFiles.isEmpty()) {
            pbOverall.setProgress(0);
            tvProgressPercent.setText("0%");
            tvProgressFiles.setText("0 / 0 Files");
            return;
        }
        long totalBytes = 0;
        long transferredBytes = 0;

        for (TransferFile file : transferFiles) {
            totalBytes += file.size;
            transferredBytes += (file.size * file.progress) / 100;
        }

        int overall = (int) ((transferredBytes * 100) / Math.max(totalBytes, 1));
        pbOverall.setProgress(overall, true);
        tvProgressPercent.setText(overall + "%");
        //tvProgressFiles.setText(completedCount + " / " + transferFiles.size() + " Files");
    }

    private TransferFile findFileByName(String name) {
        for (TransferFile file : transferFiles) {
            if (file.name.equals(name)) return file;
        }
        return null;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            List<ParallelFileServer.ManifestFile> newFilesManifest = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    addFileToTransfer(data.getClipData().getItemAt(i).getUri(), newFilesManifest);
                }
            } else if (data.getData() != null) {
                addFileToTransfer(data.getData(), newFilesManifest);
            }
            if (!newFilesManifest.isEmpty() && deviceIp != null) {
                engine.sendManifest(deviceIp, newFilesManifest);
            }
        }
    }

    private void addFileToTransfer(Uri uri, List<ParallelFileServer.ManifestFile> manifest) {
        String fileName = "file_" + System.currentTimeMillis();
        long fileSize = 0;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
            }
        } catch (Exception e) {
            Log.e(TAG, "Metadata extraction failed", e);
        }

        TransferFile file = new TransferFile(fileName, fileSize, uri);
        transferFiles.add(file);
        manifest.add(new ParallelFileServer.ManifestFile(fileName, fileSize));
        adapter.notifyItemInserted(transferFiles.size() - 1);
        updateOverallProgress();
        startTransfer(file);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private static class TransferFile {
        String name;
        long size;
        Uri uri;
        int progress = 0;
        String status = "Waiting...";

        TransferFile(String name, long size, Uri uri) {
            this.name = name;
            this.size = size;
            this.uri = uri;
        }
    }

    private static class FileTransferAdapter extends RecyclerView.Adapter<FileTransferAdapter.ViewHolder> {
        private final List<TransferFile> files;

        FileTransferAdapter(List<TransferFile> files) {
            this.files = files;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer_file, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TransferFile file = files.get(position);
            holder.tvName.setText(file.name);
            holder.tvStatus.setText(file.status);
            holder.progressBar.setProgress(file.progress);
            String sizeStr = file.size > 1024 * 1024 ? String.format("%.1f MB", file.size / (1024f * 1024f)) : String.format("%.1f KB", file.size / 1024f);
            holder.tvSize.setText(sizeStr);
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvStatus, tvSize;
            ProgressBar progressBar;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvFileName);
                tvStatus = itemView.findViewById(R.id.tvTransferStatus);
                tvSize = itemView.findViewById(R.id.tvFileSize);
                progressBar = itemView.findViewById(R.id.pbTransfer);
            }
        }
    }
}