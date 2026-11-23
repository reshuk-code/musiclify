package com.musiclify.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.musiclify.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shareable images (Instagram-story sized, 1080x1920): a song card with an optional lyric line,
 * and a listening-stats card. Drawn with a plain Canvas, saved to the cache and handed to the
 * system share sheet through a FileProvider.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1920

    suspend fun shareSong(context: Context, title: String, artist: String, coverUrl: String?, lyric: String? = null) {
        val file = withContext(Dispatchers.IO) {
            val cover = loadCover(context, coverUrl)
            val (top, bottom) = colors(cover)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            background(c, top, bottom)

            val size = 760f
            val left = (W - size) / 2
            val coverTop = if (lyric == null) 400f else 260f
            drawCover(c, cover, RectF(left, coverTop, left + size, coverTop + size))

            var y = coverTop + size + 90
            y = drawText(c, context, title, 72f, bold = true, y = y, maxLines = 2)
            y = drawText(c, context, artist, 46f, bold = false, y = y + 12, maxLines = 1, alpha = 200)
            if (lyric != null) {
                drawText(c, context, "“$lyric”", 58f, bold = true, y = y + 70, maxLines = 4, color = 0xFFFFC4B3.toInt())
            }
            footer(c, context)
            save(context, bmp)
        }
        send(context, file, "$title · $artist")
    }

    suspend fun shareStats(context: Context, period: String, stats: ListeningStats) {
        val file = withContext(Dispatchers.IO) {
            val topCover = stats.topSongs.firstOrNull()?.song?.let { it.coverBig.ifEmpty { it.cover } }
            val cover = loadCover(context, topCover)
            val (top, bottom) = colors(cover)
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            background(c, top, bottom)

            var y = drawText(c, context, "My $period in music", 64f, bold = true, y = 170f, maxLines = 2)
            val size = 520f
            drawCover(c, cover, RectF((W - size) / 2, y + 50, (W + size) / 2, y + 50 + size))
            y += 50 + size + 80
            y = drawText(c, context, "%,d minutes".format(stats.minutes), 96f, bold = true, y = y, maxLines = 1)
            y = drawText(c, context, "${stats.plays} plays · ${stats.songCount} songs", 42f, bold = false, y = y + 6, maxLines = 1, alpha = 200)

            y += 70
            y = drawText(c, context, "Top songs", 40f, bold = true, y = y, maxLines = 1, color = 0xFFFFC4B3.toInt())
            stats.topSongs.take(5).forEachIndexed { i, s ->
                y = drawText(c, context, "${i + 1}. ${s.song.title} · ${s.song.artist}", 42f, bold = false, y = y + 14, maxLines = 1)
            }
            stats.topArtists.firstOrNull()?.let {
                y += 50
                y = drawText(c, context, "Top artist", 40f, bold = true, y = y, maxLines = 1, color = 0xFFFFC4B3.toInt())
                drawText(c, context, it.artist, 60f, bold = true, y = y + 10, maxLines = 1)
            }
            footer(c, context)
            save(context, bmp)
        }
        send(context, file, "My $period in music")
    }

    // ---------------------------------------------------------------- drawing

    private suspend fun loadCover(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        return runCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull()
    }

    /** Two background colours picked from the cover (dark enough for white text). */
    private fun colors(cover: Bitmap?): Pair<Int, Int> {
        val fallback = 0xFF3A2A33.toInt() to 0xFF121417.toInt()
        cover ?: return fallback
        val p = Palette.from(cover).generate()
        val top = p.getDarkVibrantColor(p.getDarkMutedColor(fallback.first))
        return top to 0xFF0E1013.toInt()
    }

    private fun background(c: Canvas, top: Int, bottom: Int) {
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
    }

    private fun drawCover(c: Canvas, cover: Bitmap?, rect: RectF) {
        val clip = Path().apply { addRoundRect(rect, 36f, 36f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        if (cover != null) c.drawBitmap(cover, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        else c.drawRect(rect, Paint().apply { color = 0x33FFFFFF })
        c.restore()
    }

    /** Centred, wrapped text starting at [y]; returns the y just below it. */
    private fun drawText(
        c: Canvas, context: Context, text: String, size: Float, bold: Boolean, y: Float,
        maxLines: Int, alpha: Int = 255, color: Int = 0xFFFFFFFF.toInt(),
    ): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = alpha
            textSize = size
            typeface = font(context, bold)
        }
        val width = W - 160
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(80f, y)
        layout.draw(c)
        c.restore()
        return y + layout.height
    }

    private fun footer(c: Canvas, context: Context) {
        drawText(c, context, "Musiclify", 44f, bold = true, y = H - 150f, maxLines = 1, alpha = 220)
    }

    private fun font(context: Context, bold: Boolean): Typeface {
        val base = runCatching { ResourcesCompat.getFont(context, if (bold) R.font.space_grotesk else R.font.inter) }.getOrNull()
            ?: Typeface.DEFAULT
        return Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    // ---------------------------------------------------------------- sharing

    private fun save(context: Context, bmp: Bitmap): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest card is kept
        val file = File(dir, "musiclify-${System.currentTimeMillis()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun send(context: Context, file: File, text: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
