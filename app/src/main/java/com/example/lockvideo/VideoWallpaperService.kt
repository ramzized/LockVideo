package com.example.lockvideo

import android.media.MediaPlayer
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine() {
        private var player: MediaPlayer? = null
        private var current: String? = null
        private var surfaceReady = false

        private fun savedUri() = getSharedPreferences("p", MODE_PRIVATE).getString("uri", null)

        private fun startPlayback() {
            release()
            val uri = savedUri() ?: return
            if (!surfaceReady) return
            try {
                player = MediaPlayer().apply {
                    setSurface(surfaceHolder.surface)
                    setDataSource(applicationContext, Uri.parse(uri))
                    setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    isLooping = true
                    setVolume(0f, 0f)
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
                current = uri
            } catch (e: Exception) {
                release()
            }
        }

        private fun release() {
            player?.release()
            player = null
            current = null
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            startPlayback()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            release()
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
            release()
            super.onDestroy()
        }
    }
}
