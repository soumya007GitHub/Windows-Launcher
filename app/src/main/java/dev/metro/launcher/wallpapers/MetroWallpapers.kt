package dev.metro.launcher.wallpapers

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.physics.metroTilt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A wallpaper bundled with Metro (assets/wallpapers), with the artist named in the file. */
data class BuiltInWallpaper(val assetPath: String, val artist: String)

object MetroWallpapers {

    private const val DIR = "wallpapers"

    fun all(context: Context): List<BuiltInWallpaper> =
        runCatching { context.assets.list(DIR).orEmpty().sorted() }.getOrDefault(emptyList())
            .filter { it.endsWith(".jpg", ignoreCase = true) }
            .map { name ->
                BuiltInWallpaper("$DIR/$name", name.substringAfter('_').substringBeforeLast('.'))
            }

    fun random(context: Context): BuiltInWallpaper? = all(context).randomOrNull()

    /** Small preview for the gallery strip. */
    suspend fun thumbnail(context: Context, wallpaper: BuiltInWallpaper, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(wallpaper.assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.assets.open(wallpaper.assetPath).use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
            }.getOrNull()
        }
}

/**
 * A row of Metro's built-in wallpapers. Tapping one makes it the home screen photo, or the lock
 * screen photo when [forLock] is true.
 */
@Composable
fun WallpaperStrip(accent: Color, forLock: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LauncherStore.get(context) }
    val scope = rememberCoroutineScope()
    val wallpapers = remember { MetroWallpapers.all(context) }
    var chosen by remember { mutableStateOf<String?>(null) }
    val thumbWidthPx = (72 * context.resources.displayMetrics.density).toInt()

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(wallpapers, key = { it.assetPath }) { wallpaper ->
            val thumb by produceState<ImageBitmap?>(null, wallpaper) {
                value = MetroWallpapers.thumbnail(context, wallpaper, thumbWidthPx)
            }
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .width(72.dp)
                    .height(120.dp)
                    .metroTilt()
                    .background(Color(0xFF333333))
                    .then(if (chosen == wallpaper.assetPath) Modifier.border(3.dp, accent) else Modifier)
                    .clickable(noRipple, indication = null) {
                        chosen = wallpaper.assetPath
                        scope.launch { store.useBuiltInWallpaper(wallpaper.assetPath, forLock) }
                    },
            ) {
                thumb?.let {
                    Image(it, contentDescription = "wallpaper by ${wallpaper.artist}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
