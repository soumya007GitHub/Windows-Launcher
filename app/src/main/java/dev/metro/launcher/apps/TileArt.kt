package dev.metro.launcher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-tile artwork for apps that have no white glyph, the way WP games (Halo, Subway Surfers)
 * filled their whole tile with their own art instead of sitting as a small icon on the accent.
 *
 * Adaptive icons: the background layer covers the whole tile and the foreground (the logo) is
 * centred at the tile's height, so the brand colour runs edge to edge on small, medium and wide tiles.
 * Legacy icons: the tile takes the icon's own edge colour and the icon sits centred on it.
 */
object TileArt {

    private const val LAYER_UNITS = 108f
    private const val VISIBLE_UNITS = 72f

    /** Rendered artwork keyed by app + pixel size; a few dozen tiles at most are on Start. */
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(app: AppEntry, width: Int, height: Int): ImageBitmap? = cache.get(key(app, width, height))

    suspend fun render(app: AppEntry, width: Int, height: Int): ImageBitmap? {
        if (width <= 0 || height <= 0) return null
        cache.get(key(app, width, height))?.let { return it }
        val drawable = app.drawable ?: return null
        val art = withContext(Dispatchers.Default) {
            runCatching {
                if (drawable is AdaptiveIconDrawable) adaptive(drawable, width, height) else legacy(drawable, width, height)
            }.getOrNull()
        } ?: return null
        cache.put(key(app, width, height), art)
        return art
    }

    private fun key(app: AppEntry, width: Int, height: Int) = "${app.key}@${width}x$height"

    private fun adaptive(icon: AdaptiveIconDrawable, width: Int, height: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Background: cover the whole tile.
        icon.background?.let { layer ->
            val side = (max(width, height) * LAYER_UNITS / VISIBLE_UNITS).roundToInt()
            drawCentered(canvas, layer, side, width, height)
        }
        // Foreground (logo): sized to the tile's height, so wide tiles show the logo, not a crop of it.
        icon.foreground?.let { layer ->
            val side = (minOf(width, height) * LAYER_UNITS / VISIBLE_UNITS).roundToInt()
            drawCentered(canvas, layer, side, width, height)
        }
        return bitmap.asImageBitmap()
    }

    private fun legacy(icon: Drawable, width: Int, height: Int): ImageBitmap {
        val sample = icon.toBitmap(96, 96)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(edgeColor(sample))
        val side = (minOf(width, height) * 0.56f).roundToInt()
        drawCentered(canvas, icon, side, width, height)
        return bitmap.asImageBitmap()
    }

    private fun drawCentered(canvas: Canvas, layer: Drawable, side: Int, width: Int, height: Int) {
        val copy = layer.constantState?.newDrawable()?.mutate() ?: layer
        val left = (width - side) / 2
        val top = (height - side) / 2
        copy.setBounds(left, top, left + side, top + side)
        copy.draw(canvas)
    }

    /** Average opaque colour along the icon's border: the colour a legacy icon "sits on". */
    private fun edgeColor(sample: Bitmap): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val w = sample.width
        val h = sample.height
        val inset = (w * 0.12f).toInt()
        fun take(x: Int, y: Int) {
            val p = sample.getPixel(x, y)
            if ((p ushr 24) > 200) {
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
        }
        for (i in inset until w - inset) {
            take(i, inset); take(i, h - 1 - inset)
        }
        for (j in inset until h - inset) {
            take(inset, j); take(w - 1 - inset, j)
        }
        if (n == 0) return android.graphics.Color.rgb(40, 40, 40)
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** True when the tile should show full artwork rather than an icon on the accent colour. */
    fun usesArt(app: AppEntry, metroIcons: Boolean): Boolean =
        metroIcons && app.packIcon == null && app.glyph == null && app.drawable != null

    val Fallback = Color(0xFF282828)
}
