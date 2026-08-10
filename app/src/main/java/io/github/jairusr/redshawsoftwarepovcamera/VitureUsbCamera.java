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
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

/** Owns Android's USB permission flow and the file descriptor required by VITURE's SDK. */
final class VitureUsbCamera {
    interface Listener {
        void onCameraOpened(UsbDevice device, UsbDeviceConnection connection);
        void onCameraDetached();
        void onCameraError(String message);
    }

    static final int VITURE_CAMERA_VID = 0x0C45;
    static final int CAMERA_PID_LUMA = 0x636B;
    static final int CAMERA_PID_BEAST = 0x6368;

    private static final String ACTION_USB_PERMISSION =
            "io.github.jairusr.redshawsoftwarepovcamera.USB_PERMISSION";

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private UsbDevice activeDevice;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            UsbDevice device = getUsbDevice(intent);
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (device == null || !isVitureCamera(device)) {
                    return;
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    open(device);
                } else {
                    listener.onCameraError("USB access was denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (device != null && isVitureCamera(device)) {
                    requestPermission(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device != null && activeDevice != null &&
                        device.getDeviceId() == activeDevice.getDeviceId()) {
                    activeDevice = null;
                    listener.onCameraDetached();
                }
            }
        }
    };

    VitureUsbCamera(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void start() {
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        }
        scan();
    }

    void scan() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isVitureCamera(device)) {
                requestPermission(device);
                return;
            }
        }
        listener.onCameraError("Connect a VITURE camera-equipped pair of glasses");
    }

    void stop() {
        activeDevice = null;
        if (receiverRegistered) {
            context.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }

    private void requestPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            open(device);
            return;
        }
        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, permissionIntent, flags);
        usbManager.requestPermission(device, pendingIntent);
    }

    private void open(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            listener.onCameraError("Could not open the VITURE camera USB device");
            return;
        }
        activeDevice = device;
        listener.onCameraOpened(device, connection);
    }

    private static boolean isVitureCamera(UsbDevice device) {
        if (device.getVendorId() != VITURE_CAMERA_VID) {
            return false;
        }
        int pid = device.getProductId();
        return pid == CAMERA_PID_LUMA || pid == CAMERA_PID_BEAST;
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
