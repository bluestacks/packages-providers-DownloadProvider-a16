/*
 * Copyright (C) 2026 BlueStacks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import static com.android.providers.downloads.Constants.TAG;
import static com.android.providers.downloads.Helpers.getAsyncHandler;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Process;
import android.provider.Downloads;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Handles host requests to retry pending downloads without exporting system broadcast handlers. */
public class RetryDownloadReceiver extends BroadcastReceiver {
    private static final long ALL_DOWNLOADS = -1;
    private static final String ACCESS_DOWNLOAD_MANAGER_ADVANCED =
            "android.permission.ACCESS_DOWNLOAD_MANAGER_ADVANCED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Constants.ACTION_RETRY_DOWNLOADS.equals(intent.getAction())) {
            return;
        }

        final int senderUid = getSentFromUid();
        if (!isCallerAllowed(context, senderUid)) {
            Log.w(TAG, "Ignoring retry request from uid " + senderUid);
            return;
        }

        final long downloadId = intent.getLongExtra(
                DownloadManager.EXTRA_DOWNLOAD_ID, ALL_DOWNLOADS);
        final PendingResult result = goAsync();
        getAsyncHandler().post(() -> {
            try {
                handleRetryDownloads(context, downloadId);
            } finally {
                result.finish();
            }
        });
    }

    private static boolean isCallerAllowed(Context context, int uid) {
        return uid == Process.ROOT_UID
                || uid == Process.SHELL_UID
                || uid == Process.SYSTEM_UID
                || uid == Process.myUid()
                || context.checkPermission(
                        ACCESS_DOWNLOAD_MANAGER_ADVANCED,
                        -1,
                        uid) == PackageManager.PERMISSION_GRANTED;
    }

    private static void handleRetryDownloads(Context context, long downloadId) {
        final ContentResolver resolver = context.getContentResolver();
        final StringBuilder selection = new StringBuilder()
                .append(Downloads.Impl.COLUMN_STATUS)
                .append(" IN (?, ?, ?)");
        final List<String> selectionArgs = new ArrayList<>();
        selectionArgs.add(Integer.toString(Downloads.Impl.STATUS_WAITING_TO_RETRY));
        selectionArgs.add(Integer.toString(Downloads.Impl.STATUS_WAITING_FOR_NETWORK));
        selectionArgs.add(Integer.toString(Downloads.Impl.STATUS_QUEUED_FOR_WIFI));
        if (downloadId != ALL_DOWNLOADS) {
            selection.append(" AND ").append(Downloads.Impl._ID).append(" = ?");
            selectionArgs.add(Long.toString(downloadId));
        }
        final String[] args = selectionArgs.toArray(new String[0]);

        final ContentValues values = new ContentValues();
        values.put(Downloads.Impl.COLUMN_FAILED_CONNECTIONS, 0);
        resolver.update(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, values,
                selection.toString(), args);

        try (Cursor cursor = resolver.query(Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI,
                null, selection.toString(), args, null)) {
            if (cursor == null) {
                Log.w(TAG, "Unable to query downloads for retry");
                return;
            }
            final DownloadInfo.Reader reader = new DownloadInfo.Reader(resolver, cursor);
            final DownloadInfo info = new DownloadInfo(context);
            while (cursor.moveToNext()) {
                reader.updateFromDatabase(info);
                Helpers.scheduleJob(context, info);
            }
        }
    }
}
