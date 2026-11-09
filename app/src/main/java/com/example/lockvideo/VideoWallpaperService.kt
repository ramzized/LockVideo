package com.example.lockvideo

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** يرجع [نصف العرض, نصف الارتفاع, الإزاحة س, الإزاحة ص] بإحداثيات الشاشة من -1 إلى 1 */
fun videoRect(sw: Float, sh: Float, vw: Float, vh: Float, zoom: Float, px: Float, py: Float): FloatArray {
    val sa = sw / sh
    val va = vw / vh
    var wf = 1f
    var hf = 1f
    if (va > sa) wf = va / sa else hf = sa / va
    wf *= zoom
    hf *= zoom
    val tx = if (wf >= 1f) (1f - 2f * px) * (wf - 1f) else (2f * px - 1f) * (1f - wf)
    val ty = if (hf >= 1f) (2f * py - 1f) * (hf - 1f) else (1f - 2f * py) * (1f - hf)
    return floatArrayOf(wf, hf, tx, ty)
}

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(), SurfaceTexture.OnFrameAvailableListener {
        private val handler = Handler(Looper.getMainLooper())
        private var eglDisplay: EGLDisplay? = null
        private var eglCtx: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        private var program = 0
        private var texId = 0
        private var texture: SurfaceTexture? = null
        private var videoSurface: Surface? = null
        private var player: MediaPlayer? = null
        private var current: String? = null
        private var sw = 1
        private var sh = 1
        private var vw = 1
        private var vh = 1
        private val tm = FloatArray(16)
        private val texCoords = makeBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        private val pos = makeBuf(FloatArray(8))

        private fun makeBuf(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a)
                position(0)
            }

        private fun savedUri() = getSharedPreferences("p", MODE_PRIVATE).getString("uri", null)

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        private fun makeProgram(): Int {
            val vs = shader(
                GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPos;\nattribute vec2 aTex;\nuniform mat4 uTex;\nvarying vec2 vTex;\n" +
                    "void main(){gl_Position=aPos;vTex=(uTex*vec4(aTex,0.0,1.0)).xy;}"
            )
            val fs = shader(
                GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n" +
                    "varying vec2 vTex;\nuniform samplerExternalOES sTex;\n" +
                    "void main(){gl_FragColor=texture2D(sTex,vTex);}"
            )
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            return p
        }

        private fun initGl(surface: Surface) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(eglDisplay, null, 0, null, 0)
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfgs, 0, 1, IntArray(1), 0)
            eglCtx = EGL14.eglCreateContext(
                eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglCtx)
            program = makeProgram()

            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texId = t[0]
            val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(target, texId)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(texId)
            st.setOnFrameAvailableListener(this, handler)
            texture = st
        }

        private fun releaseGl() {
            texture?.release()
            texture = null
            val d = eglDisplay
            if (d != null) {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglSurface?.let { EGL14.eglDestroySurface(d, it) }
                eglCtx?.let { EGL14.eglDestroyContext(d, it) }
                EGL14.eglTerminate(d)
            }
            eglDisplay = null
            eglSurface = null
            eglCtx = null
        }

        private fun draw() {
            val st = texture ?: return
            val d = eglDisplay ?: return
            val s = eglSurface ?: return
            st.updateTexImage()
            st.getTransformMatrix(tm)

            val pr = getSharedPreferences("p", MODE_PRIVATE)
            val r = videoRect(
                sw.toFloat(), sh.toFloat(), vw.toFloat(), vh.toFloat(),
                pr.getFloat("zoom", 1f), pr.getFloat("px", 0.5f), pr.getFloat("py", 0.5f)
            )
            val l = r[2] - r[0]
            val rt = r[2] + r[0]
            val b = r[3] - r[1]
            val t = r[3] + r[1]
            pos.position(0)
            pos.put(floatArrayOf(l, b, rt, b, l, t, rt, t))
            pos.position(0)

            GLES20.glViewport(0, 0, sw, sh)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aTex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, tm, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(d, s)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            try {
                draw()
            } catch (e: Exception) {
            }
        }

        private fun releasePlayer() {
            player?.release()
            player = null
            videoSurface?.release()
            videoSurface = null
            current = null
        }

        private fun startPlayback() {
            releasePlayer()
            val st = texture ?: return
            val uri = savedUri() ?: return
            try {
                val vs = Surface(st)
                videoSurface = vs
                player = MediaPlayer().apply {
                    setSurface(vs)
                    setDataSource(applicationContext, Uri.parse(uri))
                    isLooping = true
                    setVolume(0f, 0f)
                    setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) {
                            vw = w
                            vh = h
                        }
                    }
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
                current = uri
            } catch (e: Exception) {
                releasePlayer()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            initGl(holder.surface)
            startPlayback()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            sw = width
            sh = height
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            handler.removeCallbacksAndMessages(null)
            releaseGl()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                if (player == null || savedUri() != current) startPlayback() else player?.start()
            } else {
                player?.pause()
            }
        }

        override fun onDestroy() {
            releasePlayer()
            handler.removeCallbacksAndMessages(null)
            releaseGl()
            super.onDestroy()
        }
    }
}
