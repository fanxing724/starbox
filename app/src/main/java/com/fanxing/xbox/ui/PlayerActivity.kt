package com.fanxing.xbox.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.fanxing.xbox.databinding.ActivityPlayerBinding
import java.io.File

/**
 * 本地视频播放：Media3/ExoPlayer（Apache 2.0，纯 JVM，无 native 依赖）。
 * 支持本地文件与直链在线播放（含 HLS）。
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(this)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra("path")
        val url = intent.getStringExtra("url")
        val uri: Uri? = when {
            !path.isNullOrBlank() -> Uri.fromFile(File(path))
            !url.isNullOrBlank() -> Uri.parse(url)
            else -> null
        }
        if (uri == null) {
            Toast.makeText(this, "没有可播放的地址", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        player = ExoPlayer.Builder(this).build().also { p ->
            b.playerView.player = p
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        b.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
