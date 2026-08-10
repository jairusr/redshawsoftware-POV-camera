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

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Draws the ordinary Android camera UI directly on the glasses presentation display.
 * A 3840x1200 side-by-side display receives one identical view per eye; conventional
 * presentation displays receive a single view.
 */
final class GlassesPresentation extends Presentation {
    interface Listener {
        void onTakePhoto();
        void onToggleRecording();
        void onRetry();
    }

    private final Listener listener;
    private final List<SurfaceView> previewViews = new ArrayList<>();
    private final List<TextView> statusViews = new ArrayList<>();
    private final List<TextView> recordingViews = new ArrayList<>();
    private final List<Button> photoButtons = new ArrayList<>();
    private final List<Button> recordButtons = new ArrayList<>();
    private final List<Button> retryButtons = new ArrayList<>();

    GlassesPresentation(Context context, Display display, Listener listener) {
        super(context, display);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        Display.Mode mode = getDisplay().getMode();
        int eyeCount = mode.getPhysicalWidth() >= mode.getPhysicalHeight() * 2.4f ? 2 : 1;
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int eye = 0; eye < eyeCount; ++eye) {
            View eyeView = inflater.inflate(R.layout.activity_viture_pov, root, false);
            root.addView(eyeView, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            bindEye(eyeView);
        }
        setContentView(root);
    }

    private void bindEye(View eyeView) {
        SurfaceView preview = eyeView.findViewById(R.id.viture_preview);
        TextView status = eyeView.findViewById(R.id.viture_status);
        TextView recording = eyeView.findViewById(R.id.viture_recording_status);
        Button photo = eyeView.findViewById(R.id.viture_photo);
        Button record = eyeView.findViewById(R.id.viture_record);
        Button retry = eyeView.findViewById(R.id.viture_retry);

        previewViews.add(preview);
        statusViews.add(status);
        recordingViews.add(recording);
        photoButtons.add(photo);
        recordButtons.add(record);
        retryButtons.add(retry);

        photo.setOnClickListener(view -> listener.onTakePhoto());
        record.setOnClickListener(view -> listener.onToggleRecording());
        retry.setOnClickListener(view -> listener.onRetry());
    }

    List<SurfaceView> getPreviewViews() {
        if (previewViews.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(previewViews);
    }

    void setStatus(CharSequence status) {
        for (TextView view : statusViews) {
            view.setText(status);
        }
    }

    void setCaptureEnabled(boolean enabled) {
        for (Button button : photoButtons) {
            button.setEnabled(enabled);
        }
        for (Button button : recordButtons) {
            button.setEnabled(enabled);
        }
    }

    void setRecording(CharSequence elapsed, boolean recording) {
        for (TextView view : recordingViews) {
            view.setText(elapsed);
            view.setVisibility(recording ? View.VISIBLE : View.GONE);
        }
        for (Button button : recordButtons) {
            button.setText(recording
                    ? R.string.viture_stop_recording
                    : R.string.viture_start_recording);
        }
    }

    void setRetryVisible(boolean visible) {
        for (Button button : retryButtons) {
            button.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
