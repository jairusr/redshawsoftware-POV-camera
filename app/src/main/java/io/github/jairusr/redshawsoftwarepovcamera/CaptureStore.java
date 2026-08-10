/*
 * Redshaw Software POV Camera
 * Copyright (C) 2026 Redshaw Software POV Camera contributors
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package io.github.jairusr.redshawsoftwarepovcamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Scoped-storage and legacy-storage helpers for POV photos and videos. */
final class CaptureStore {
    private static final String RELATIVE_DIRECTORY = Environment.DIRECTORY_DCIM +
            "/RedshawSoftwarePOVCamera";

    static final class VideoTarget {
        private final Context context;
        private final Uri uri;
        private final ParcelFileDescriptor descriptor;
        private final File legacyFile;
        final String displayName;

        VideoTarget(Context context, Uri uri, ParcelFileDescriptor descriptor,
                    File legacyFile, String displayName) {
            this.context = context.getApplicationContext();
            this.uri = uri;
            this.descriptor = descriptor;
            this.legacyFile = legacyFile;
            this.displayName = displayName;
        }

        PovRecorder createRecorder(PovRecorder.FailureListener listener) throws IOException {
            if (descriptor != null) {
                return new PovRecorder(context, descriptor.getFileDescriptor(), listener);
            }
            return new PovRecorder(context, legacyFile.getAbsolutePath(), listener);
        }

        void finish(boolean success) {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                ContentResolver resolver = context.getContentResolver();
                if (success) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            } else if (legacyFile != null) {
                if (success) {
                    MediaScannerConnection.scanFile(context,
                            new String[]{legacyFile.getAbsolutePath()},
                            new String[]{"video/mp4"}, null);
                } else if (legacyFile.exists() && !legacyFile.delete()) {
                    legacyFile.deleteOnExit();
                }
            }
        }
    }

    private CaptureStore() {
    }

    static String savePhoto(Context context, byte[] jpeg) throws IOException {
        String name = createName("IMG_", ".jpg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIRECTORY);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore refused the new photo");
            }
            boolean success = false;
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) {
                    throw new IOException("Could not open the new photo");
                }
                output.write(jpeg);
                success = true;
            } finally {
                if (success) {
                    ContentValues complete = new ContentValues();
                    complete.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, complete, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
            return name;
        }

        File directory = legacyDirectory();
        File file = new File(directory, name);
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(jpeg);
        }
        MediaScannerConnection.scanFile(context,
                new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return name;
    }

    static VideoTarget createVideo(Context context) throws IOException {
        String name = createName("VID_", ".mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIRECTORY);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore refused the new video");
            }
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw");
            if (descriptor == null) {
                resolver.delete(uri, null, null);
                throw new IOException("Could not open the new video");
            }
            return new VideoTarget(context, uri, descriptor, null, name);
        }

        File file = new File(legacyDirectory(), name);
        return new VideoTarget(context, null, null, file, name);
    }

    private static File legacyDirectory() throws IOException {
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File directory = new File(dcim, "RedshawSoftwarePOVCamera");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory.getAbsolutePath());
        }
        return directory;
    }

    private static String createName(String prefix, String suffix) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date());
        return prefix + timestamp + suffix;
    }
}
