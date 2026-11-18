package dev.metro.launcher.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Turns an Android launcher icon into a white Windows Phone–style glyph.
 *
 * 1. Android 13+ "themed icon" monochrome layer: designed by the app's author as a single-colour
 *    glyph, so it is exactly what a Metro tile wants.
 * 2. Otherwise the adaptive icon's foreground layer as a white silhouette, but only if it actually
 *    looks like a glyph (mostly empty space). Full-bleed pictures or legacy square icons return
 *    null and the tile keeps the original colour icon.
 */
object MetroGlyphs {

    /** Adaptive icon layers are 108 units. */
    private const val LAYER_UNITS = 108f

    /**
     * Themed-icon glyphs are drawn inside Android's safe zone with generous padding; cropping to the
     * centre 56 units makes the glyph fill the bitmap, so on a tile it reaches WP's glyph size.
     */
    private const val VISIBLE_UNITS = 56f

    fun render(icon: Drawable, sizePx: Int): ImageBitmap? {
        if (icon !is AdaptiveIconDrawable) return null
        val monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) icon.monochrome else null
        val layer = monochrome ?: icon.foreground ?: return null
        val bitmap = drawWhite(layer, sizePx)
        if (monochrome == null && !looksLikeGlyph(bitmap)) {
            bitmap.recycle()
            return null
        }
        return bitmap.asImageBitmap()
    }

    private fun drawWhite(layer: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val copy = layer.constantState?.newDrawable()?.mutate() ?: layer
        val overflow = ((LAYER_UNITS - VISIBLE_UNITS) / 2f / VISIBLE_UNITS * sizePx).toInt()
        copy.setBounds(-overflow, -overflow, sizePx + overflow, sizePx + overflow)
        copy.draw(canvas)
        // Keep the shape (alpha), paint everything white.
        canvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC_IN)
        return bitmap
    }

    /** A glyph covers a modest share of the tile; a picture or a filled shape covers most of it. */
    private fun looksLikeGlyph(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var opaque = 0
        for (p in pixels) if ((p ushr 24) > 128) opaque++
        val coverage = opaque.toFloat() / pixels.size
        return coverage in 0.04f..0.45f
    }
}
