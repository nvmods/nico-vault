package fr.nicovape.nicofiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import android.media.ThumbnailUtils
import java.io.File
import java.util.concurrent.Executors

object ThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(file: File, target: ImageView, placeholder: Int) {
        val key = "${file.absolutePath}:${file.lastModified()}"
        target.tag = key
        target.setImageResource(placeholder)

        cache.get(key)?.let {
            target.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = runCatching {
                when {
                    file.isImage() -> decodeImage(file)
                    file.isVideo() -> decodeVideo(file)
                    else -> null
                }
            }.getOrNull()

            if (bitmap != null) {
                cache.put(key, bitmap)
                mainHandler.post {
                    if (target.tag == key) target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun decodeVideo(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ThumbnailUtils.createVideoThumbnail(file, Size(240, 240), null)
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.frameAtTime
        } finally {
            runCatching { retriever.release() }
        }
    }
}
