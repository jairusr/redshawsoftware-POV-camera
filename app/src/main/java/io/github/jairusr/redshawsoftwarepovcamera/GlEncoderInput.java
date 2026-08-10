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

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Uploads decoded camera frames to a MediaCodec input surface through OpenGL ES. */
final class GlEncoderInput {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int FLOATS_PER_VERTEX = 4;
    private static final int BYTES_PER_FLOAT = 4;

    // x, y, u, v. Bitmap rows are top-to-bottom, so texture V is flipped for GL.
    private static final float[] FULL_SCREEN_QUAD = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    private final Surface codecSurface;
    private final EGLDisplay display;
    private final EGLContext context;
    private final EGLSurface surface;
    private final FloatBuffer vertices;
    private final int program;
    private final int positionLocation;
    private final int textureLocation;
    private final int textureId;
    private boolean textureAllocated;
    private boolean released;

    GlEncoderInput(Surface codecSurface) {
        this.codecSurface = codecSurface;
        vertices = ByteBuffer.allocateDirect(FULL_SCREEN_QUAD.length * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertices.put(FULL_SCREEN_QUAD).position(0);

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("No EGL display");
        }
        int[] versions = new int[2];
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            throw new IllegalStateException("Could not initialize EGL");
        }

        int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(display, configAttributes, 0,
                configs, 0, configs.length, configCount, 0) || configCount[0] == 0) {
            throw new IllegalStateException("No recordable EGL config");
        }

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttributes, 0);
        checkEgl("eglCreateContext");
        int[] surfaceAttributes = {EGL14.EGL_NONE};
        surface = EGL14.eglCreateWindowSurface(
                display, configs[0], codecSurface, surfaceAttributes, 0);
        checkEgl("eglCreateWindowSurface");

        makeCurrent();
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
        textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
        int samplerLocation = GLES20.glGetUniformLocation(program, "uTexture");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(samplerLocation, 0);
        checkGl("GL setup");
        detachCurrent();
    }

    void draw(Bitmap bitmap, long presentationTimeNs) {
        if (released) {
            throw new IllegalStateException("Encoder surface is released");
        }
        makeCurrent();
        try {
            GLES20.glViewport(0, 0,
                    VitureCameraBridge.CAMERA_WIDTH, VitureCameraBridge.CAMERA_HEIGHT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            if (!textureAllocated) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                textureAllocated = true;
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            }

            vertices.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false,
                    FLOATS_PER_VERTEX * BYTES_PER_FLOAT, vertices);
            vertices.position(2);
            GLES20.glEnableVertexAttribArray(textureLocation);
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false,
                    FLOATS_PER_VERTEX * BYTES_PER_FLOAT, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGl("draw frame");

            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs);
            if (!EGL14.eglSwapBuffers(display, surface)) {
                throw new IllegalStateException("eglSwapBuffers failed: 0x" +
                        Integer.toHexString(EGL14.eglGetError()));
            }
        } finally {
            detachCurrent();
        }
    }

    void release() {
        if (released) {
            return;
        }
        released = true;
        makeCurrent();
        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        GLES20.glDeleteProgram(program);
        detachCurrent();
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
        codecSurface.release();
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent failed: 0x" +
                    Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private void detachCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Could not link GL program: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile GL shader: " + log);
        }
        return shader;
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(operation + " failed: 0x" +
                    Integer.toHexString(error));
        }
    }

    private static void checkEgl(String operation) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException(operation + " failed: 0x" +
                    Integer.toHexString(error));
        }
    }
}
