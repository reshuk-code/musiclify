package com.musiclify.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.musiclify.MainActivity
import com.musiclify.R
import java.io.File

/**
 * Home-screen player widget. The playback service pushes the current song with [update];
 * the widget keeps the last state on disk so it still shows the song after the app is closed.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private const val PREFS = "widget"

        /** Saves the playing song and redraws every widget. Call from the playback service. */
        suspend fun update(context: Context, title: String, artist: String, artworkUri: String?, playing: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (artworkUri != prefs.getString("art_uri", null)) {
                loadArtwork(context, artworkUri)?.let { saveArtwork(context, it) } ?: artFile(context).delete()
            }
            prefs.edit {
                putString("title", title)
                putString("artist", artist)
                putString("art_uri", artworkUri)
                putBoolean("playing", playing)
            }
            redraw(context)
        }

        /** The service is going away: controls would have nothing to talk to, so show "paused". */
        fun stopped(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("playing", false); putBoolean("alive", false) }
            redraw(context)
        }

        fun started(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("alive", true) }
        }

        private fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString("title", null)
            val playing = prefs.getBoolean("playing", false)
            // Buttons only work while the playback service is around; otherwise they open the app.
            val controllable = title != null && prefs.getBoolean("alive", false)

            val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.widget_title, title ?: context.getString(R.string.widget_nothing_playing))
                setTextViewText(R.id.widget_artist, prefs.getString("artist", null) ?: context.getString(R.string.widget_tap_to_open))
                val art = artFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                if (art != null) setImageViewBitmap(R.id.widget_art, art) else setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setContentDescription(R.id.widget_play,
                    context.getString(if (playing) R.string.widget_pause else R.string.widget_play))

                val open = openApp(context)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_play, if (controllable) mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else open)
                setOnClickPendingIntent(R.id.widget_next, if (controllable) mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT) else open)
                setOnClickPendingIntent(R.id.widget_prev, if (controllable) mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) else open)
            }
            manager.updateAppWidget(ids, views)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** A media-button press delivered to the media session (Media3 routes it to the player). */
        @OptIn(UnstableApi::class)
        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun artFile(context: Context) = File(context.cacheDir, "widget_art.png")

        private suspend fun loadArtwork(context: Context, uri: String?): Bitmap? {
            if (uri.isNullOrBlank()) return null
            val request = ImageRequest.Builder(context).data(uri).size(256).allowHardware(false).build()
            return runCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull()
        }

        private fun saveArtwork(context: Context, bitmap: Bitmap) {
            runCatching { artFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
