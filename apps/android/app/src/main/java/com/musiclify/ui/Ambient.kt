package com.musiclify.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.musiclify.playback.BeatMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Fallback = listOf(Color(0xFFFF7A59), Color(0xFFFF4D6D), Color(0xFF7A5CFF))

/** Three vivid colours from the cover art (the app's accent palette until it loads). */
@Composable
fun rememberArtworkColors(url: String?): List<Color> {
    val context = LocalContext.current
    val colors by produceState(Fallback, url) {
        if (url.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
            val bitmap = runCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull()
            bitmap?.let(::paletteColors) ?: Fallback
        }
    }
    return colors
}

fun paletteColors(bitmap: Bitmap): List<Color> {
    val p = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatches = listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch, p.dominantSwatch)
        .map { Color(it.rgb) }.distinct()
    return (swatches + Fallback).take(3)
}

/**
 * Cover-coloured glow that pulses with the beat. [strength] scales it: subtle behind the app,
 * stronger behind the full-screen player. Honours the system "remove animations" setting.
 */
@Composable
fun AmbientBackground(vm: AppViewModel, strength: Float, modifier: Modifier = Modifier) {
    if (!vm.ambientLight || vm.player.currentId == null) return
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val raw = rememberArtworkColors(vm.player.currentArtwork)
    val c1 by animateColorAsState(raw[0], tween(1500), label = "c1")
    val c2 by animateColorAsState(raw[1], tween(1500), label = "c2")
    val c3 by animateColorAsState(raw[2], tween(1500), label = "c3")

    val playing = vm.player.isPlaying
    var beat by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, reduceMotion) {
        while (true) {
            withFrameNanos { }
            beat = if (playing && !reduceMotion) BeatMeter.level else beat * 0.9f
        }
    }

    Canvas(modifier) {
        val base = if (playing) 0.55f else 0.35f
        val alpha = (base + beat * 0.45f) * strength
        val grow = 1f + beat * 0.08f
        fun glow(color: Color, x: Float, y: Float, r: Float) {
            val radius = size.maxDimension * r * grow
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent),
                    center = Offset(size.width * x, size.height * y), radius = radius),
                radius = radius, center = Offset(size.width * x, size.height * y),
            )
        }
        glow(c1, 0.2f, 0.12f, 0.55f)
        glow(c2, 0.9f, 0.3f, 0.45f)
        glow(c3, 0.5f, 1.0f, 0.6f)
    }
}
