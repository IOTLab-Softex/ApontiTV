package br.com.softextv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlin.concurrent.thread

object RemoteImageLoader {
    private val memoryCache = object : LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount / 1024
    }

    fun loadInto(imageView: ImageView, url: String?, onLoaded: (() -> Unit)? = null) {
        if (imageView.tag == url && imageView.drawable != null) {
            onLoaded?.invoke()
            return
        }

        imageView.tag = url
        if (url.isNullOrBlank()) return

        memoryCache.get(url)?.let { cachedBitmap ->
            imageView.setImageBitmap(cachedBitmap)
            onLoaded?.invoke()
            return
        }

        thread {
            runCatching {
                decodeScaledBitmap(url, imageView)
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    memoryCache.put(url, bitmap)
                    imageView.post {
                        if (imageView.tag == url) {
                            imageView.setImageBitmap(bitmap)
                            onLoaded?.invoke()
                        }
                    }
                }
            }
        }
    }

    private fun decodeScaledBitmap(url: String, imageView: ImageView): Bitmap? {
        val targetWidth = (imageView.width.takeIf { it > 0 } ?: imageView.resources.displayMetrics.widthPixels).coerceAtLeast(720)
        val targetHeight = (imageView.height.takeIf { it > 0 } ?: imageView.resources.displayMetrics.heightPixels).coerceAtLeast(720)

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        java.net.URL(url).openStream().use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return java.net.URL(url).openStream().use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }
}
