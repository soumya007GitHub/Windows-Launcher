package dev.metro.launcher.start

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import dev.metro.launcher.apps.TileArt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.notifications.NotificationPreview
import dev.metro.launcher.physics.TileSize
import dev.metro.launcher.ui.MetroType

data class StartTileModel(
    val app: AppEntry,
    val size: TileSize,
    val metroIcons: Boolean = true,
    /** Per-tile icon choice from the icon picker (null = automatic). */
    val iconOverride: String? = null,
) {
    val key: String get() = app.key
}

private val CutoutScrim = Color.Black.copy(alpha = 0.14f)

/**
 * Transparent-tiles source: the background photo already centre-cropped to the window, plus the
 * scroll / pan states whose changes move tiles (so tiles repaint while Start scrolls or pans).
 */
class CutoutWallpaper(
    val image: ImageBitmap,
    val scroll: ScrollState,
    val pager: PagerState?,
)

/** Centre-crops [photo] to exactly [size] (the window), like ContentScale.Crop. */
fun cropToSize(photo: ImageBitmap, size: IntSize): ImageBitmap {
    val source = photo.asAndroidBitmap()
    val scale = max(size.width / source.width.toFloat(), size.height / source.height.toFloat())
    val scaledW = (source.width * scale).roundToInt().coerceAtLeast(size.width)
    val scaledH = (source.height * scale).roundToInt().coerceAtLeast(size.height)
    val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
    val cropped = Bitmap.createBitmap(scaled, (scaledW - size.width) / 2, (scaledH - size.height) / 2, size.width, size.height)
    if (scaled !== cropped && scaled !== source) scaled.recycle()
    return cropped.asImageBitmap()
}

/**
 * Tile background. With transparent tiles, each tile paints the part of the window-sized photo that
 * lies behind it, so the photo stays fixed while tiles scroll over it (WP 8.1 "show-through" tiles).
 * The photo is drawn by the tile itself, so nothing depends on how Android composites layers;
 * the old hole-punching approach flickered when Android gave a tile its own layer mid-scroll.
 */
fun Modifier.tileSurface(accent: Color, cutout: CutoutWallpaper?): Modifier =
    if (cutout == null) {
        background(accent)
    } else {
        composed {
            val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
            onPlaced { coordinates[0] = it }.drawBehind {
                // Reading these subscribes the draw to scrolling and panning.
                cutout.scroll.value
                cutout.pager?.currentPageOffsetFraction
                val coords = coordinates[0]?.takeIf { it.isAttached }
                if (coords != null) {
                    val image = cutout.image
                    val origin = coords.positionInWindow()
                    val x = origin.x.roundToInt()
                    val y = origin.y.roundToInt()
                    val w = size.width.roundToInt()
                    val h = size.height.roundToInt()
                    // Only the part of the tile that is inside the window has photo behind it.
                    val left = max(x, 0)
                    val top = max(y, 0)
                    val right = min(x + w, image.width)
                    val bottom = min(y + h, image.height)
                    if (right > left && bottom > top) {
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(left, top),
                            srcSize = IntSize(right - left, bottom - top),
                            dstOffset = IntOffset(left - x, top - y),
                            dstSize = IntSize(right - left, bottom - top),
                        )
                    }
                }
                drawRect(CutoutScrim)
            }
        }
    }

/**
 * Tile front, chosen the way WP tiles looked:
 *  - a white glyph (themed icon or icon pack) on the accent, at WP glyph size, slightly above centre,
 *    with a notification count beside it;
 *  - otherwise the app's own artwork filling the whole tile (like WP game tiles);
 *  - with Metro icons off, the original colour icon on the accent.
 */
@Composable
fun TileFront(tile: StartTileModel, preview: NotificationPreview?, accent: Color, cutout: CutoutWallpaper?) {
    val app = tile.app
    val count = preview?.count ?: 0
    val override = tile.iconOverride
    if (override?.startsWith("image|") == true) {
        ImageTile(tile, override, count)
        return
    }
    if (override == "art" || (override == null && TileArt.usesArt(app, tile.metroIcons))) {
        ArtTile(tile, count)
        return
    }
    val context = LocalContext.current
    val packSpec = override?.takeIf { it.startsWith("pack|") }
    val packIcon by produceState(packSpec?.let { CustomIcons.cached(it) }, packSpec) {
        if (packSpec != null && value == null) value = CustomIcons.load(context, packSpec)
    }
    val icon: ImageBitmap = when {
        packSpec != null -> packIcon ?: app.icon
        override == "original" -> app.icon
        override == "glyph" -> app.glyph ?: app.tileIcon(true)
        else -> app.tileIcon(tile.metroIcons)
    }
    val hasGlyph = when {
        packSpec != null -> true
        override == "original" -> false
        override == "glyph" -> app.glyph != null
        else -> tile.metroIcons && (app.packIcon != null || app.glyph != null)
    }
    val iconFraction = when {
        tile.size == TileSize.Small -> if (hasGlyph) 0.56f else 0.5f
        hasGlyph -> 0.46f
        else -> 0.36f
    }
    Box(
        Modifier
            .fillMaxSize()
            .tileSurface(accent, cutout)
            .clipToBounds(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight(iconFraction)
                .offset(y = if (tile.size == TileSize.Small) 0.dp else (-6).dp),
        ) {
            Image(
                bitmap = icon,
                contentDescription = app.label,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
            )
            // WP: the count sits beside the glyph ("3", "21").
            if (count > 0 && tile.size != TileSize.Small) {
                BasicText(
                    text = if (count > 99) "99+" else count.toString(),
                    style = MetroType.TileLabel.copy(fontSize = 34.sp, fontWeight = FontWeight.Light),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (count > 0 && tile.size == TileSize.Small) {
            BasicText(
                text = if (count > 99) "99+" else count.toString(),
                style = MetroType.TileLabel.copy(fontSize = 12.sp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
            )
        }
        if (tile.size != TileSize.Small) {
            BasicText(
                text = tile.app.label,
                style = MetroType.TileLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 6.dp, end = 8.dp),
            )
        }
    }
}

/** A user-chosen image across the whole tile (cropped to fill), label on top. */
@Composable
private fun ImageTile(tile: StartTileModel, spec: String, count: Int) {
    val context = LocalContext.current
    val image by produceState(CustomIcons.cached(spec), spec) {
        if (value == null) value = CustomIcons.load(context, spec)
    }
    Box(Modifier.fillMaxSize().background(TileArt.Fallback).clipToBounds()) {
        image?.let {
            Image(it, contentDescription = tile.app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (tile.size != TileSize.Small) {
            BasicText(
                text = tile.app.label,
                style = MetroType.TileLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp, end = 30.dp),
            )
        }
        if (count > 0) {
            BasicText(
                text = if (count > 99) "99+" else count.toString(),
                style = MetroType.TileLabel.copy(fontSize = 15.sp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp),
            )
        }
    }
}

/** The app's own artwork across the whole tile (rendered at the tile's exact pixel size), label on top. */
@Composable
private fun ArtTile(tile: StartTileModel, count: Int) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val art by produceState(TileArt.cached(tile.app, size.width, size.height), tile.app.key, size) {
        if (size != IntSize.Zero) value = TileArt.render(tile.app, size.width, size.height)
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .background(TileArt.Fallback)
            .clipToBounds(),
    ) {
        art?.let { Image(it, contentDescription = tile.app.label, modifier = Modifier.fillMaxSize()) }
        if (tile.size != TileSize.Small) {
            BasicText(
                text = tile.app.label,
                style = MetroType.TileLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 6.dp, end = 30.dp),
            )
        }
        if (count > 0) {
            BasicText(
                text = if (count > 99) "99+" else count.toString(),
                style = MetroType.TileLabel.copy(fontSize = if (tile.size == TileSize.Small) 12.sp else 15.sp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp),
            )
        }
    }
}

/** Square icon for the app list / search rows: glyph on accent, or the app's artwork filling the square. */
@Composable
fun AppSquare(app: AppEntry, accent: Color, metroIcons: Boolean, modifier: Modifier, glyphFraction: Float = 0.62f) {
    if (TileArt.usesArt(app, metroIcons)) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val art by produceState(TileArt.cached(app, size.width, size.height), app.key, size) {
            if (size != IntSize.Zero) value = TileArt.render(app, size.width, size.height)
        }
        Box(modifier.onSizeChanged { size = it }.background(TileArt.Fallback)) {
            art?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
    } else {
        Box(contentAlignment = Alignment.Center, modifier = modifier.background(accent)) {
            Image(
                app.tileIcon(metroIcons),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(glyphFraction),
            )
        }
    }
}

@Composable
fun TileBack(tile: StartTileModel, preview: NotificationPreview, accent: Color, cutout: CutoutWallpaper?) {
    Column(
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .fillMaxSize()
            .tileSurface(accent, cutout)
            .clipToBounds()
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 6.dp),
    ) {
        BasicText(
            text = preview.title.ifBlank { tile.app.label },
            style = MetroType.Medium.copy(color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            text = preview.text,
            style = MetroType.Small.copy(color = Color.White, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Light),
            maxLines = if (tile.size == TileSize.Wide) 3 else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BasicText(tile.app.label, style = MetroType.TileLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
