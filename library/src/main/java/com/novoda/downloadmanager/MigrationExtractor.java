package com.novoda.downloadmanager;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class MigrationExtractor {

    private static final String BATCHES_QUERY = "SELECT batches._id, batches.batch_title, batches.last_modified_timestamp FROM "
            + "batches INNER JOIN DownloadsByBatch ON DownloadsByBatch.batch_id = batches._id "
            + "WHERE DownloadsByBatch.batch_total_bytes = DownloadsByBatch.batch_current_bytes GROUP BY batches._id";
    private static final int BATCH_ID_COLUMN = 0;
    private static final int TITLE_COLUMN = 1;
    private static final int MODIFIED_TIMESTAMP_COLUMN = 2;

    private static final String DOWNLOADS_QUERY = "SELECT uri, _data, notificationextras FROM Downloads WHERE batch_id = ?";
    private static final int NETWORK_ADDRESS_COLUMN = 0;
    private static final int FILE_LOCATION_COLUMN = 1;
    private static final int FILE_ID_COLUMN = 2;

    private final SqlDatabaseWrapper database;
    private final FilePersistence filePersistence;

    MigrationExtractor(SqlDatabaseWrapper database, FilePersistence filePersistence) {
        this.database = database;
        this.filePersistence = filePersistence;
    }

    List<Migration> extractMigrations() {
        Cursor batchesCursor = database.rawQuery(BATCHES_QUERY);

        if (batchesCursor == null) {
            return Collections.emptyList();
        }

        try {
            List<Migration> migrations = new ArrayList<>();

            while (batchesCursor.moveToNext()) {
                String batchId = batchesCursor.getString(BATCH_ID_COLUMN);
                String batchTitle = batchesCursor.getString(TITLE_COLUMN);
                long downloadedDateTimeInMillis = batchesCursor.getLong(MODIFIED_TIMESTAMP_COLUMN);

                Cursor downloadsCursor = database.rawQuery(DOWNLOADS_QUERY, batchId);

                if (downloadsCursor == null) {
                    return Collections.emptyList();
                }

                Batch.Builder newBatchBuilder = null;
                List<Migration.FileMetadata> fileMetadataList = new ArrayList<>();

                try {
                    while (downloadsCursor.moveToNext()) {
                        String originalFileId = downloadsCursor.getString(FILE_ID_COLUMN);
                        String originalNetworkAddress = downloadsCursor.getString(NETWORK_ADDRESS_COLUMN);
                        String originalFileLocation = downloadsCursor.getString(FILE_LOCATION_COLUMN);

                        if (downloadsCursor.isFirst()) {
                            DownloadBatchId downloadBatchId = createDownloadBatchIdFrom(originalFileId, batchId);
                            newBatchBuilder = Batch.with(downloadBatchId, batchTitle);
                        }

                        newBatchBuilder.addFile(originalNetworkAddress).apply();

                        FilePath filePath = new LiteFilePath(originalFileLocation);
                        long rawFileSize = filePersistence.getCurrentSize(filePath);
                        FileSize fileSize = new LiteFileSize(rawFileSize, rawFileSize);
                        Migration.FileMetadata fileMetadata = new Migration.FileMetadata(
                                originalFileId,
                                originalFileLocation,
                                fileSize,
                                originalNetworkAddress
                        );
                        fileMetadataList.add(fileMetadata);
                    }
                } finally {
                    downloadsCursor.close();
                }

                Batch batch = newBatchBuilder.build();
                migrations.add(new Migration(batch, fileMetadataList, downloadedDateTimeInMillis, Migration.Type.COMPLETE));
            }

            return migrations;
        } finally {
            batchesCursor.close();
        }
    }

    private DownloadBatchId createDownloadBatchIdFrom(String originalFileId, String batchId) {
        if (originalFileId == null || originalFileId.isEmpty()) {
            return DownloadBatchIdCreator.createFrom(batchId);
        }
        return DownloadBatchIdCreator.createFrom(originalFileId);
    }

}
