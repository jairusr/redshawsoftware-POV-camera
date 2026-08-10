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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encodes decoded VITURE MJPEG frames and microphone PCM into an H.264/AAC MP4. */
final class PovRecorder {
    interface FailureListener {
        void onRecorderFailure(String message, Exception exception);
    }

    private static final String TAG = "PovRecorder";
    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int VIDEO_BIT_RATE = 12_000_000;
    private static final int DEFAULT_AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 1;
    private static final int AUDIO_BIT_RATE = 128_000;
    private static final long CODEC_TIMEOUT_US = 10_000;

    private static final class PendingSample {
        final boolean video;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        PendingSample(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.video = video;
            this.data = data;
            this.info = info;
        }
    }

    private final MediaMuxer muxer;
    private final Context context;
    private final FailureListener failureListener;
    private final Object muxerLock = new Object();
    private final Object videoLock = new Object();
    private final List<PendingSample> pendingSamples = new ArrayList<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean audioRunning = new AtomicBoolean();

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private AudioDeviceInfo preferredMicrophone;
    private AudioDeviceInfo fallbackMicrophone;
    private GlEncoderInput glEncoderInput;
    private Thread audioThread;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted;
    private boolean audioRouteLogged;
    private int audioSampleRate = DEFAULT_AUDIO_SAMPLE_RATE;
    private long startTimeNs;
    private long lastVideoPtsUs = -1;
    private long lastAudioPtsUs = -1;

    // CaptureStore only uses this descriptor path on Android 10 and newer.
    @SuppressLint("NewApi")
    PovRecorder(Context context, FileDescriptor descriptor,
                FailureListener failureListener) throws IOException {
        this.context = context.getApplicationContext();
        this.muxer = new MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.failureListener = failureListener;
    }

    PovRecorder(Context context, String path, FailureListener failureListener) throws IOException {
        this.context = context.getApplicationContext();
        this.muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        this.failureListener = failureListener;
    }

    void start() throws IOException {
        try {
            startInternal();
        } catch (IOException | RuntimeException exception) {
            releaseCodecsAndMuxer();
            throw exception;
        }
    }

    @SuppressLint("MissingPermission")
    private void startInternal() throws IOException {
        preferredMicrophone = findUsbMicrophone();
        fallbackMicrophone = findBuiltInMicrophone();
        audioSampleRate = selectSampleRate(preferredMicrophone);

        MediaFormat videoFormat = MediaFormat.createVideoFormat(
                VIDEO_MIME, VitureCameraBridge.CAMERA_WIDTH, VitureCameraBridge.CAMERA_HEIGHT);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VitureCameraBridge.CAMERA_FPS);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaFormat audioFormat = MediaFormat.createAudioFormat(
                AUDIO_MIME, audioSampleRate, AUDIO_CHANNELS);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);

        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        glEncoderInput = new GlEncoderInput(videoEncoder.createInputSurface());
        videoEncoder.start();

        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormatPcm = AudioFormat.ENCODING_PCM_16BIT;
        int minimumBuffer = AudioRecord.getMinBufferSize(
                audioSampleRate, channelConfig, audioFormatPcm);
        if (minimumBuffer <= 0) {
            throw new IOException("The microphone does not support " + audioSampleRate +
                    " Hz mono PCM");
        }
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                audioSampleRate,
                channelConfig,
                audioFormatPcm,
                Math.max(minimumBuffer * 2, 16 * 1024));
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("The microphone could not be initialized");
        }
        configurePreferredMicrophone();

        audioRecord.startRecording();
        probePreferredMicrophone();
        startTimeNs = System.nanoTime();
        audioRunning.set(true);
        audioThread = new Thread(this::runAudio, "ViturePovAudio");
        audioThread.start();
    }

    void submitVideoFrame(Bitmap bitmap, long captureTimeNs) {
        if (stopping.get()) {
            return;
        }
        synchronized (videoLock) {
            if (stopping.get() || videoEncoder == null) {
                return;
            }
            try {
                long ptsUs = Math.max(lastVideoPtsUs + 1,
                        Math.max(0, (captureTimeNs - startTimeNs) / 1_000));
                lastVideoPtsUs = ptsUs;
                glEncoderInput.draw(bitmap, ptsUs * 1_000);
                drainEncoder(videoEncoder, true, false);
            } catch (RuntimeException exception) {
                reportFailure("Video encoding failed", exception);
            }
        }
    }

    void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        audioRunning.set(false);
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (audioThread != null) {
            try {
                audioThread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (videoLock) {
            queueVideoEndOfStream();
        }
        releaseCodecsAndMuxer();
    }

    private void runAudio() {
        try {
            while (audioRunning.get()) {
                int inputIndex = audioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                if (inputIndex < 0) {
                    drainEncoder(audioEncoder, false, false);
                    continue;
                }
                ByteBuffer input = audioEncoder.getInputBuffer(inputIndex);
                if (input == null) {
                    audioEncoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                    continue;
                }
                input.clear();
                int bytesRead = audioRecord.read(input, input.remaining());
                if (bytesRead <= 0) {
                    audioEncoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                    continue;
                }
                logActiveMicrophone();
                long durationUs = bytesRead * 1_000_000L /
                        (audioSampleRate * AUDIO_CHANNELS * 2L);
                long nowUs = Math.max(0, (System.nanoTime() - startTimeNs) / 1_000);
                long ptsUs = Math.max(lastAudioPtsUs + 1, Math.max(0, nowUs - durationUs));
                lastAudioPtsUs = ptsUs;
                audioEncoder.queueInputBuffer(inputIndex, 0, bytesRead, ptsUs, 0);
                drainEncoder(audioEncoder, false, false);
            }
            queueAudioEndOfStream();
        } catch (RuntimeException exception) {
            reportFailure("Microphone recording failed", exception);
        }
    }

    private AudioDeviceInfo findUsbMicrophone() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            boolean usbInput = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            type == AudioDeviceInfo.TYPE_USB_HEADSET);
            if (usbInput) {
                return device;
            }
        }
        return null;
    }

    private AudioDeviceInfo findBuiltInMicrophone() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return device;
            }
        }
        return null;
    }

    private static int selectSampleRate(AudioDeviceInfo device) {
        if (device == null) {
            return DEFAULT_AUDIO_SAMPLE_RATE;
        }
        int[] sampleRates = device.getSampleRates();
        if (sampleRates.length == 0) {
            return DEFAULT_AUDIO_SAMPLE_RATE;
        }
        int selected = sampleRates[0];
        for (int sampleRate : sampleRates) {
            if (sampleRate == DEFAULT_AUDIO_SAMPLE_RATE) {
                return sampleRate;
            }
            selected = Math.max(selected, sampleRate);
        }
        return selected;
    }

    private void configurePreferredMicrophone() {
        if (preferredMicrophone == null) {
            Log.i(TAG, "No USB microphone detected; using the Android host microphone at " +
                    audioSampleRate + " Hz");
            return;
        }
        if (audioRecord.setPreferredDevice(preferredMicrophone)) {
            Log.i(TAG, "Preferred USB microphone: " + preferredMicrophone.getProductName() +
                    " at " + audioSampleRate + " Hz");
        } else {
            Log.w(TAG, "Android rejected the preferred USB microphone; using the default route");
        }
    }

    private void probePreferredMicrophone() {
        if (preferredMicrophone == null) {
            return;
        }
        int probeBytes = Math.max(2_048, audioSampleRate / 2); // 250 ms of mono PCM16.
        ByteBuffer probe = ByteBuffer.allocateDirect(Math.min(probeBytes, 16 * 1_024));
        int capturedBytes = 0;
        int peakAmplitude = 0;
        for (int attempt = 0; capturedBytes < probeBytes && attempt < 20; ++attempt) {
            probe.clear();
            int requested = Math.min(probe.capacity(), probeBytes - capturedBytes);
            int bytesRead = audioRecord.read(probe, requested);
            if (bytesRead > 0) {
                peakAmplitude = Math.max(peakAmplitude, peakAmplitude(probe, bytesRead));
                capturedBytes += bytesRead;
            }
        }
        if (peakAmplitude > 0) {
            Log.i(TAG, "USB microphone signal detected (peak " + peakAmplitude + ")");
            return;
        }
        if (fallbackMicrophone != null && audioRecord.setPreferredDevice(fallbackMicrophone)) {
            Log.w(TAG, "USB microphone returned digital silence; switched to " +
                    fallbackMicrophone.getProductName());
            preferredMicrophone = fallbackMicrophone;
            audioRouteLogged = false;
            // Discard a short transition window before official A/V timestamps begin.
            int transitionBytes = Math.max(2_048, audioSampleRate / 2);
            int discardedBytes = 0;
            while (discardedBytes < transitionBytes) {
                probe.clear();
                int requested = Math.min(probe.capacity(), transitionBytes - discardedBytes);
                int bytesRead = audioRecord.read(probe, requested);
                if (bytesRead <= 0) {
                    break;
                }
                discardedBytes += bytesRead;
            }
        } else {
            Log.w(TAG, "USB microphone returned digital silence and no host fallback is available");
        }
    }

    private static int peakAmplitude(ByteBuffer input, int bytesRead) {
        ByteBuffer samples = input.duplicate().order(ByteOrder.nativeOrder());
        int sampleBytes = bytesRead & ~1;
        int peak = 0;
        for (int offset = 0; offset < sampleBytes; offset += 2) {
            peak = Math.max(peak, Math.abs((int) samples.getShort(offset)));
        }
        return peak;
    }

    private void logActiveMicrophone() {
        if (audioRouteLogged) {
            return;
        }
        audioRouteLogged = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AudioDeviceInfo routedDevice = audioRecord.getRoutedDevice();
            if (routedDevice != null) {
                Log.i(TAG, "Recording microphone: " + routedDevice.getProductName() +
                        " (type " + routedDevice.getType() + ")");
                return;
            }
        }
        Log.i(TAG, "Recording from the default Android microphone route");
    }

    private void queueAudioEndOfStream() {
        if (audioEncoder == null) {
            return;
        }
        for (int attempt = 0; attempt < 50; ++attempt) {
            int inputIndex = audioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputIndex >= 0) {
                long ptsUs = Math.max(lastAudioPtsUs + 1,
                        Math.max(0, (System.nanoTime() - startTimeNs) / 1_000));
                audioEncoder.queueInputBuffer(inputIndex, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drainEncoder(audioEncoder, false, true);
                return;
            }
            drainEncoder(audioEncoder, false, false);
        }
    }

    private void queueVideoEndOfStream() {
        if (videoEncoder == null) {
            return;
        }
        videoEncoder.signalEndOfInputStream();
        drainEncoder(videoEncoder, true, true);
    }

    private void drainEncoder(MediaCodec codec, boolean video, boolean waitForEos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int emptyPolls = 0;
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, waitForEos ? CODEC_TIMEOUT_US : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEos || ++emptyPolls >= 100) {
                    return;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                registerTrack(video, codec.getOutputFormat());
            } else if (outputIndex >= 0) {
                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0 &&
                        (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    writeOrQueueSample(video, output, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputIndex, false);
                if (eos) {
                    return;
                }
            }
        }
    }

    private void registerTrack(boolean video, MediaFormat format) {
        synchronized (muxerLock) {
            if (video) {
                if (videoTrack < 0) {
                    videoTrack = muxer.addTrack(format);
                }
            } else if (audioTrack < 0) {
                audioTrack = muxer.addTrack(format);
            }
            if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
                muxer.start();
                muxerStarted = true;
                Collections.sort(pendingSamples, (first, second) -> Long.compare(
                        first.info.presentationTimeUs, second.info.presentationTimeUs));
                for (PendingSample sample : pendingSamples) {
                    muxer.writeSampleData(sample.video ? videoTrack : audioTrack,
                            sample.data, sample.info);
                }
                pendingSamples.clear();
            }
        }
    }

    private void writeOrQueueSample(boolean video, ByteBuffer source, MediaCodec.BufferInfo sourceInfo) {
        synchronized (muxerLock) {
            if (muxerStarted) {
                muxer.writeSampleData(video ? videoTrack : audioTrack, source, sourceInfo);
                return;
            }

            ByteBuffer copy = ByteBuffer.allocateDirect(sourceInfo.size);
            copy.put(source);
            copy.flip();
            MediaCodec.BufferInfo infoCopy = new MediaCodec.BufferInfo();
            infoCopy.set(0, sourceInfo.size, sourceInfo.presentationTimeUs, sourceInfo.flags);
            pendingSamples.add(new PendingSample(video, copy, infoCopy));
        }
    }

    private void releaseCodecsAndMuxer() {
        if (glEncoderInput != null) {
            glEncoderInput.release();
            glEncoderInput = null;
        }
        releaseCodec(videoEncoder);
        releaseCodec(audioEncoder);
        videoEncoder = null;
        audioEncoder = null;
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        synchronized (muxerLock) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not finalize MP4", exception);
            } finally {
                muxer.release();
                pendingSamples.clear();
            }
        }
    }

    private static void releaseCodec(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        codec.release();
    }

    private void reportFailure(String message, Exception exception) {
        Log.e(TAG, message, exception);
        if (failureListener != null) {
            failureListener.onRecorderFailure(message, exception);
        }
    }
}
