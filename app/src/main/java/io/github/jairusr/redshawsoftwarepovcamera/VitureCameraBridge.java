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

/** JNI access to the VITURE pass-through camera provider. */
final class VitureCameraBridge {
    static final int CAMERA_WIDTH = 1920;
    static final int CAMERA_HEIGHT = 1080;
    static final int CAMERA_FPS = 30;

    static {
        // This is optional at build time and installed locally by the SDK installer.
        try {
            System.loadLibrary("glasses");
        } catch (UnsatisfiedLinkError ignored) {
            // nativeLoadSdk() reports the actionable error to the UI.
        }
        System.loadLibrary("viture_camera_bridge");
    }

    static final class Frame {
        final byte[] jpeg;
        final long timestampNs;
        final int sequence;

        Frame(byte[] jpeg, long timestampNs, int sequence) {
            this.jpeg = jpeg;
            this.timestampNs = timestampNs;
            this.sequence = sequence;
        }
    }

    private VitureCameraBridge() {
    }

    static native boolean nativeLoadSdk();
    static native boolean nativeIsValidCamera(int vendorId, int productId);
    static native boolean nativeCreate(int vendorId, int productId, int fileDescriptor);
    static native int nativeStart();
    static native int nativeStop();
    static native void nativeDestroy();
    static native Frame nativeAwaitFrame(int timeoutMs);
}
