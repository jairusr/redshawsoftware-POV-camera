/*
 * Redshaw Software POV Camera
 * Copyright (C) 2026 Redshaw Software POV Camera contributors
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * The VITURE SDK is loaded dynamically so its proprietary binary is never part
 * of this source distribution. The ABI declarations below mirror VITURE's
 * publicly documented camera-provider API.
 */

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "VitureCameraBridge";

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)

using CameraHandle = void*;

enum CameraFormat : int {
    CAMERA_FORMAT_MJPEG = 0,
};

struct CameraFrame {
    uint8_t* data;
    uint32_t size;
    uint32_t width;
    uint32_t height;
    CameraFormat format;
    uint64_t timestamp;
    uint32_t sequence;
};

using CameraFrameCallback = void (*)(const CameraFrame*, void*);
using IsValidCameraFn = int (*)(int, int);
using CameraCreateFn = CameraHandle (*)(int, int, int);
using CameraStartFn = int (*)(CameraHandle, CameraFrameCallback, void*);
using CameraStopFn = int (*)(CameraHandle);
using CameraDestroyFn = void (*)(CameraHandle);

struct SdkApi {
    void* library = nullptr;
    IsValidCameraFn is_valid_camera = nullptr;
    CameraCreateFn create = nullptr;
    CameraStartFn start = nullptr;
    CameraStopFn stop = nullptr;
    CameraDestroyFn destroy = nullptr;

    bool loaded() const {
        return library && is_valid_camera && create && start && stop && destroy;
    }
};

SdkApi g_sdk;
std::mutex g_sdk_mutex;
CameraHandle g_camera = nullptr;

struct LatestFrame {
    std::mutex mutex;
    std::condition_variable available;
    std::vector<uint8_t> jpeg;
    uint64_t timestamp_ns = 0;
    uint32_t sequence = 0;
    uint64_t generation = 0;
    uint64_t delivered_generation = 0;
    bool streaming = false;
};

LatestFrame g_frame;

template <typename T>
bool loadSymbol(void* library, const char* name, T& target) {
    target = reinterpret_cast<T>(dlsym(library, name));
    if (!target) {
        LOGE("Missing VITURE SDK symbol %s: %s", name, dlerror());
        return false;
    }
    return true;
}

bool loadSdkLocked() {
    if (g_sdk.loaded()) {
        return true;
    }

    void* library = dlopen("libglasses.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        LOGE("Unable to load libglasses.so: %s", dlerror());
        return false;
    }

    SdkApi loaded;
    loaded.library = library;
    if (!loadSymbol(library, "xr_camera_provider_is_valid_camera", loaded.is_valid_camera) ||
        !loadSymbol(library, "xr_camera_provider_create", loaded.create) ||
        !loadSymbol(library, "xr_camera_provider_start", loaded.start) ||
        !loadSymbol(library, "xr_camera_provider_stop", loaded.stop) ||
        !loadSymbol(library, "xr_camera_provider_destroy", loaded.destroy)) {
        dlclose(library);
        return false;
    }

    g_sdk = loaded;
    LOGI("VITURE camera SDK loaded");
    return true;
}

void onCameraFrame(const CameraFrame* frame, void*) {
    if (!frame || !frame->data || frame->size == 0 || frame->format != CAMERA_FORMAT_MJPEG) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(g_frame.mutex);
        g_frame.jpeg.assign(frame->data, frame->data + frame->size);
        g_frame.timestamp_ns = frame->timestamp;
        g_frame.sequence = frame->sequence;
        ++g_frame.generation;
    }
    g_frame.available.notify_one();
}

void clearFrames(bool streaming) {
    {
        std::lock_guard<std::mutex> lock(g_frame.mutex);
        g_frame.jpeg.clear();
        g_frame.timestamp_ns = 0;
        g_frame.sequence = 0;
        g_frame.generation = 0;
        g_frame.delivered_generation = 0;
        g_frame.streaming = streaming;
    }
    g_frame.available.notify_all();
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeLoadSdk(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    return loadSdkLocked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeIsValidCamera(
        JNIEnv*, jclass, jint vendor_id, jint product_id) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    return loadSdkLocked() && g_sdk.is_valid_camera(vendor_id, product_id) == 1
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeCreate(
        JNIEnv*, jclass, jint vendor_id, jint product_id, jint file_descriptor) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    if (!loadSdkLocked()) {
        return JNI_FALSE;
    }
    if (g_camera) {
        g_sdk.destroy(g_camera);
        g_camera = nullptr;
    }
    clearFrames(false);
    g_camera = g_sdk.create(vendor_id, product_id, file_descriptor);
    return g_camera ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeStart(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    if (!g_camera || !g_sdk.loaded()) {
        return -1;
    }
    clearFrames(true);
    const int result = g_sdk.start(g_camera, onCameraFrame, nullptr);
    if (result != 0) {
        clearFrames(false);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeStop(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    if (!g_camera || !g_sdk.loaded()) {
        clearFrames(false);
        return 0;
    }
    const int result = g_sdk.stop(g_camera);
    clearFrames(false);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeDestroy(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    if (g_camera && g_sdk.loaded()) {
        g_sdk.destroy(g_camera);
        g_camera = nullptr;
    }
    clearFrames(false);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_jairusr_redshawsoftwarepovcamera_VitureCameraBridge_nativeAwaitFrame(
        JNIEnv* env, jclass, jint timeout_ms) {
    std::vector<uint8_t> jpeg;
    uint64_t timestamp_ns;
    uint32_t sequence;
    {
        std::unique_lock<std::mutex> lock(g_frame.mutex);
        const bool ready = g_frame.available.wait_for(
                lock, std::chrono::milliseconds(std::max(timeout_ms, 0)), [] {
                    return !g_frame.streaming ||
                           g_frame.generation != g_frame.delivered_generation;
                });
        if (!ready || !g_frame.streaming || g_frame.jpeg.empty()) {
            return nullptr;
        }
        jpeg = g_frame.jpeg;
        timestamp_ns = g_frame.timestamp_ns;
        sequence = g_frame.sequence;
        g_frame.delivered_generation = g_frame.generation;
    }

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(jpeg.size()));
    if (!bytes) {
        return nullptr;
    }
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(jpeg.size()),
                            reinterpret_cast<const jbyte*>(jpeg.data()));
    jclass frame_class = env->FindClass(
            "io/github/jairusr/redshawsoftwarepovcamera/VitureCameraBridge$Frame");
    if (!frame_class) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(frame_class, "<init>", "([BJI)V");
    if (!constructor) {
        return nullptr;
    }
    return env->NewObject(frame_class, constructor, bytes,
                          static_cast<jlong>(timestamp_ns), static_cast<jint>(sequence));
}
