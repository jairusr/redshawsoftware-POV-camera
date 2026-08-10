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

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Low-latency POV camera UI backed by VITURE's pass-through camera provider. */
public class ViturePovActivity extends Activity implements VitureUsbCamera.Listener {
    private static final int REQUEST_CAPTURE_PERMISSIONS = 300;
    private static final int REQUEST_MICROPHONE = 301;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final Paint previewPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Object recorderLock = new Object();

    private SurfaceView previewView;
    private TextView statusView;
    private TextView recordingView;
    private Button photoButton;
    private Button recordButton;
    private Button retryButton;
    private VitureUsbCamera usbCamera;
    private DisplayManager displayManager;
    private volatile GlassesPresentation glassesPresentation;
    private UsbDeviceConnection activeConnection;
    private Thread frameThread;
    private volatile boolean frameLoopRunning;
    private volatile PovRecorder recorder;
    private CaptureStore.VideoTarget videoTarget;
    private long recordingStartedMs;
    private boolean startRecordingAfterPermission;

    private final Runnable recordingTimer = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) {
                return;
            }
            long elapsed = (android.os.SystemClock.elapsedRealtime() - recordingStartedMs) / 1_000;
            recordingView.setText(getString(R.string.viture_recording_time,
                    elapsed / 60, elapsed % 60));
            if (glassesPresentation != null) {
                glassesPresentation.setRecording(recordingView.getText(), true);
            }
            mainHandler.postDelayed(this, 500);
        }
    };

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (glassesPresentation != null
                            && glassesPresentation.getDisplay().getDisplayId() == displayId) {
                        dismissGlassesPresentation();
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viture_pov);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewView = findViewById(R.id.viture_preview);
        statusView = findViewById(R.id.viture_status);
        recordingView = findViewById(R.id.viture_recording_status);
        photoButton = findViewById(R.id.viture_photo);
        recordButton = findViewById(R.id.viture_record);
        retryButton = findViewById(R.id.viture_retry);

        photoButton.setOnClickListener(view -> takePhoto());
        recordButton.setOnClickListener(view -> toggleRecording());
        retryButton.setOnClickListener(view -> {
            retryCamera();
        });
        setCaptureButtonsEnabled(false);

        displayManager = getSystemService(DisplayManager.class);
        displayManager.registerDisplayListener(displayListener, mainHandler);

        usbCamera = new VitureUsbCamera(this, this);
        requestInitialPermissions();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(recordingTimer);
        stopCameraStream();
        if (usbCamera != null) {
            usbCamera.stop();
        }
        displayManager.unregisterDisplayListener(displayListener);
        dismissGlassesPresentation();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAPTURE_PERMISSIONS) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                beginDiscovery();
            } else {
                showError(getString(R.string.viture_camera_permission_required));
            }
        } else if (requestCode == REQUEST_MICROPHONE) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO) && startRecordingAfterPermission) {
                startRecordingAfterPermission = false;
                startRecording();
            } else {
                startRecordingAfterPermission = false;
                showError(getString(R.string.viture_microphone_permission_required));
            }
        }
    }

    @Override
    public void onCameraOpened(UsbDevice device, UsbDeviceConnection connection) {
        if (activeConnection != null) {
            connection.close();
            return;
        }
        if (!VitureCameraBridge.nativeLoadSdk()) {
            connection.close();
            showError(getString(R.string.viture_sdk_missing));
            return;
        }
        if (!VitureCameraBridge.nativeIsValidCamera(device.getVendorId(), device.getProductId())) {
            connection.close();
            showError(getString(R.string.viture_unsupported_camera));
            return;
        }
        if (!VitureCameraBridge.nativeCreate(
                device.getVendorId(), device.getProductId(), connection.getFileDescriptor())) {
            connection.close();
            showError(getString(R.string.viture_open_failed));
            return;
        }
        int result = VitureCameraBridge.nativeStart();
        if (result != 0) {
            VitureCameraBridge.nativeDestroy();
            connection.close();
            showError(getString(R.string.viture_start_failed, result));
            return;
        }

        activeConnection = connection;
        retryButton.setVisibility(View.GONE);
        if (glassesPresentation != null) {
            glassesPresentation.setRetryVisible(false);
        }
        setCaptureButtonsEnabled(true);
        status(R.string.viture_waiting_for_frames);
        startFrameLoop();
    }

    @Override
    public void onCameraDetached() {
        dismissGlassesPresentation();
        stopCameraStream();
        showError(getString(R.string.viture_camera_detached));
    }

    @Override
    public void onCameraError(String message) {
        dismissGlassesPresentation();
        showError(message);
    }

    private void requestInitialPermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.CAMERA)) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (missing.isEmpty()) {
            beginDiscovery();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQUEST_CAPTURE_PERMISSIONS);
        }
    }

    private void beginDiscovery() {
        status(R.string.viture_searching);
        usbCamera.start();
    }

    private void startFrameLoop() {
        frameLoopRunning = true;
        frameThread = new Thread(() -> {
            Bitmap reusableBitmap = null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            long fpsStarted = android.os.SystemClock.elapsedRealtime();
            int frames = 0;
            while (frameLoopRunning) {
                VitureCameraBridge.Frame frame = VitureCameraBridge.nativeAwaitFrame(500);
                if (frame == null) {
                    continue;
                }
                latestJpeg.set(frame.jpeg);
                try {
                    options.inBitmap = reusableBitmap;
                    reusableBitmap = BitmapFactory.decodeByteArray(
                            frame.jpeg, 0, frame.jpeg.length, options);
                } catch (IllegalArgumentException incompatibleBitmap) {
                    options.inBitmap = null;
                    reusableBitmap = BitmapFactory.decodeByteArray(
                            frame.jpeg, 0, frame.jpeg.length, options);
                }
                if (reusableBitmap == null) {
                    continue;
                }

                PovRecorder activeRecorder = recorder;
                if (activeRecorder != null) {
                    // Use the local monotonic clock so A/V timestamps share one timebase.
                    activeRecorder.submitVideoFrame(reusableBitmap, System.nanoTime());
                }
                GlassesPresentation presentation = glassesPresentation;
                if (presentation != null) {
                    List<SurfaceView> glassesPreviews = presentation.getPreviewViews();
                    for (SurfaceView glassesPreview : glassesPreviews) {
                        drawPreview(reusableBitmap, glassesPreview);
                    }
                    if (glassesPreviews.isEmpty()) {
                        drawPreview(reusableBitmap, previewView);
                    }
                } else {
                    drawPreview(reusableBitmap, previewView);
                }

                ++frames;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - fpsStarted >= 1_000) {
                    final int fps = Math.round(frames * 1_000f / (now - fpsStarted));
                    mainHandler.post(() -> setStatusText(
                            getString(R.string.viture_stream_status, fps)));
                    fpsStarted = now;
                    frames = 0;
                }
            }
            if (reusableBitmap != null) {
                reusableBitmap.recycle();
            }
        }, "ViturePovFrames");
        frameThread.start();
    }

    private void drawPreview(Bitmap bitmap, SurfaceView surfaceView) {
        SurfaceHolder holder = surfaceView.getHolder();
        Canvas canvas = null;
        try {
            canvas = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? holder.lockHardwareCanvas()
                    : holder.lockCanvas();
            if (canvas == null) {
                return;
            }
            float scale = Math.max(
                    canvas.getWidth() / (float) bitmap.getWidth(),
                    canvas.getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            RectF destination = new RectF(
                    (canvas.getWidth() - width) / 2f,
                    (canvas.getHeight() - height) / 2f,
                    (canvas.getWidth() + width) / 2f,
                    (canvas.getHeight() + height) / 2f);
            canvas.drawBitmap(bitmap, null, destination, previewPaint);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // The display or its SurfaceView may have been detached between frames.
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // The presentation disappeared while this frame was being posted.
                }
            }
        }
    }

    private void takePhoto() {
        byte[] jpeg = latestJpeg.get();
        if (jpeg == null) {
            Toast.makeText(this, R.string.viture_no_frame, Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] photo = jpeg.clone();
        new Thread(() -> {
            try {
                String name = CaptureStore.savePhoto(this, photo);
                mainHandler.post(() -> Toast.makeText(this,
                        getString(R.string.viture_photo_saved, name), Toast.LENGTH_SHORT).show());
            } catch (IOException exception) {
                mainHandler.post(() -> showError(exception.getMessage()));
            }
        }, "ViturePovPhoto").start();
    }

    private void toggleRecording() {
        if (recorder != null) {
            stopRecording(false);
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startRecordingAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        synchronized (recorderLock) {
            if (recorder != null || activeConnection == null) {
                return;
            }
            try {
                videoTarget = CaptureStore.createVideo(this);
                PovRecorder newRecorder = videoTarget.createRecorder((message, exception) ->
                        mainHandler.post(() -> {
                            showError(message);
                            stopRecording(true);
                        }));
                newRecorder.start();
                recorder = newRecorder;
                recordingStartedMs = android.os.SystemClock.elapsedRealtime();
                recordButton.setText(R.string.viture_stop_recording);
                recordingView.setVisibility(View.VISIBLE);
                if (glassesPresentation != null) {
                    glassesPresentation.setRecording(
                            getString(R.string.viture_recording_initial), true);
                }
                mainHandler.post(recordingTimer);
            } catch (IOException | RuntimeException exception) {
                if (videoTarget != null) {
                    videoTarget.finish(false);
                    videoTarget = null;
                }
                showError(getString(R.string.viture_record_start_failed, exception.getMessage()));
            }
        }
    }

    private void stopRecording(boolean failed) {
        final PovRecorder recorderToStop;
        final CaptureStore.VideoTarget targetToFinish;
        synchronized (recorderLock) {
            recorderToStop = recorder;
            targetToFinish = videoTarget;
            recorder = null;
            videoTarget = null;
        }
        if (recorderToStop == null) {
            return;
        }
        mainHandler.removeCallbacks(recordingTimer);
        recordButton.setText(R.string.viture_start_recording);
        recordingView.setVisibility(View.GONE);
        if (glassesPresentation != null) {
            glassesPresentation.setRecording(
                    getString(R.string.viture_recording_initial), false);
        }
        new Thread(() -> {
            recorderToStop.stop();
            if (targetToFinish != null) {
                targetToFinish.finish(!failed);
                if (!failed) {
                    mainHandler.post(() -> Toast.makeText(this,
                            getString(R.string.viture_video_saved, targetToFinish.displayName),
                            Toast.LENGTH_SHORT).show());
                }
            }
        }, "ViturePovRecorderStop").start();
    }

    private void stopCameraStream() {
        setCaptureButtonsEnabled(false);
        stopRecording(false);
        frameLoopRunning = false;
        VitureCameraBridge.nativeStop();
        if (frameThread != null) {
            try {
                frameThread.join(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            frameThread = null;
        }
        VitureCameraBridge.nativeDestroy();
        latestJpeg.set(null);
        if (activeConnection != null) {
            activeConnection.close();
            activeConnection = null;
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void setCaptureButtonsEnabled(boolean enabled) {
        photoButton.setEnabled(enabled);
        recordButton.setEnabled(enabled);
        if (glassesPresentation != null) {
            glassesPresentation.setCaptureEnabled(enabled);
        }
    }

    private void status(int stringId) {
        setStatusText(getString(stringId));
    }

    private void showError(String message) {
        setStatusText(message);
        retryButton.setVisibility(View.VISIBLE);
        if (glassesPresentation != null) {
            glassesPresentation.setRetryVisible(true);
        }
    }

    private void retryCamera() {
        retryButton.setVisibility(View.GONE);
        if (glassesPresentation != null) {
            glassesPresentation.setRetryVisible(false);
        }
        status(R.string.viture_searching);
        if (usbCamera != null) {
            usbCamera.scan();
        }
    }

    private void setStatusText(CharSequence status) {
        statusView.setText(status);
        if (glassesPresentation != null) {
            glassesPresentation.setStatus(status);
        }
    }

    private void showAttachedGlassesDisplay() {
        for (Display display : displayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (isPresentationDisplay(display)) {
                showGlassesPresentation(display);
                return;
            }
        }
    }

    private boolean isPresentationDisplay(Display display) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        for (Display presentationDisplay : displayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (presentationDisplay.getDisplayId() == display.getDisplayId()) {
                return true;
            }
        }
        return false;
    }

    private void showGlassesPresentation(Display display) {
        if (glassesPresentation != null
                && glassesPresentation.getDisplay().getDisplayId() == display.getDisplayId()) {
            return;
        }
        dismissGlassesPresentation();
        GlassesPresentation presentation = new GlassesPresentation(this, display,
                new GlassesPresentation.Listener() {
                    @Override
                    public void onTakePhoto() {
                        takePhoto();
                    }

                    @Override
                    public void onToggleRecording() {
                        toggleRecording();
                    }

                    @Override
                    public void onRetry() {
                        retryCamera();
                    }
                });
        presentation.show();
        presentation.setStatus(statusView.getText());
        presentation.setCaptureEnabled(photoButton.isEnabled());
        presentation.setRetryVisible(retryButton.getVisibility() == View.VISIBLE);
        presentation.setRecording(recordingView.getText(), recorder != null);
        glassesPresentation = presentation;
    }

    private void dismissGlassesPresentation() {
        GlassesPresentation presentation = glassesPresentation;
        glassesPresentation = null;
        if (presentation != null) {
            presentation.dismiss();
        }
    }
}
