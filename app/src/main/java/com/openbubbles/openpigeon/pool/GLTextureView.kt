package com.openbubbles.openpigeon.pool

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.TextureView
import javax.microedition.khronos.opengles.GL10

class GLTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderer: GLSurfaceView.Renderer? = null
    private var renderThread: RenderThread? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setRenderer(r: GLSurfaceView.Renderer) {
        renderer = r
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread = RenderThread(surface, width, height, renderer!!).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.quit()
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
        private val renderer: GLSurfaceView.Renderer
    ) : Thread("GLTextureView-Render") {

        @Volatile private var running = true
        @Volatile private var sizeChanged = false

        fun resize(w: Int, h: Int) { width = w; height = h; sizeChanged = true }
        fun quit() { running = false }

        override fun run() {
            // --- EGL setup ---
            val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, null, 0, null, 0)

            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,      // alpha for transparency
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context: EGLContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            val surface: EGLSurface = EGL14.eglCreateWindowSurface(display, configs[0], surfaceTexture, surfAttribs, 0)

            EGL14.eglMakeCurrent(display, surface, surface, context)

            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)

            while (running) {
                val frameStart = System.currentTimeMillis()
                if (sizeChanged) {
                    renderer.onSurfaceChanged(null, width, height)
                    sizeChanged = false
                }
                renderer.onDrawFrame(null)
                EGL14.eglSwapBuffers(display, surface)
                val elapsed = System.currentTimeMillis() - frameStart
                val remaining = 16L - elapsed
                if (remaining > 0) sleep(remaining)
            }

            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}