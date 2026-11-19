@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.metro.launcher.start

import dev.metro.launcher.ui.statusBarSpace
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.apps.IconPackInfo
import dev.metro.launcher.apps.IconPacks
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.physics.metroTilt
import dev.metro.launcher.ui.LocalMetroColors
import dev.metro.launcher.ui.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads custom icons ("pack|<pkg>|<name>" or "image|<file>") with a small in-memory cache. */
object CustomIcons {
    private val cache = LruCache<String, ImageBitmap>(96)

    fun cached(spec: String): ImageBitmap? = cache.get(spec)

    suspend fun load(context: Context, spec: String): ImageBitmap? {
        cache.get(spec)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val parts = spec.split('|')
                when (parts.firstOrNull()) {
                    "pack" -> IconPacks.loadDrawable(context, parts[1], parts[2])?.toBitmap(192, 192)?.asImageBitmap()
                    "image" -> BitmapFactory.decodeFile(LauncherStore.get(context).iconFile(parts[1]).path)?.asImageBitmap()
                    else -> null
                }
            }.getOrNull()
        } ?: return null
        cache.put(spec, bitmap)
        return bitmap
    }
}

/**
 * Per-tile icon chooser (long-press a tile, then the pencil). Options: automatic, Metro glyph,
 * full artwork, original icon, any icon from an installed icon pack (searchable), or an image.
 */
@Composable
fun IconPicker(
    app: AppEntry,
    current: String?,
    accent: Color,
    packs: List<IconPackInfo>,
    onPick: (String?) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var browsing by remember { mutableStateOf<IconPackInfo?>(null) }
    BackHandler { if (browsing != null) browsing = null else onDismiss() }
    val colors = LocalMetroColors.current

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarSpace()
            .padding(horizontal = 20.dp),
    ) {
        val pack = browsing
        if (pack == null) {
            OptionList(app, current, accent, packs, onPick, onPickImage, onBrowse = { browsing = it })
        } else {
            PackGrid(app, pack, accent, onPick)
        }
    }
}

@Composable
private fun OptionList(
    app: AppEntry,
    current: String?,
    accent: Color,
    packs: List<IconPackInfo>,
    onPick: (String?) -> Unit,
    onPickImage: () -> Unit,
    onBrowse: (IconPackInfo) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(20.dp))
            BasicText("CHOOSE ICON", style = MetroType.AppTitle)
            BasicText(app.label, style = MetroType.PageTitle.copy(fontSize = 40.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
        }
        item {
            Option("automatic", current == null, accent, preview = { AppSquare(app, accent, true, it) }) { onPick(null) }
        }
        if (app.glyph != null) {
            item {
                Option("metro glyph", current == "glyph", accent, preview = { Square(app.glyph, accent, it) }) { onPick("glyph") }
            }
        }
        if (app.drawable != null) {
            item {
                Option("full artwork", current == "art", accent, preview = { ArtSquare(app, it) }) { onPick("art") }
            }
        }
        item {
            Option("original icon", current == "original", accent, preview = { Square(app.icon, accent, it, 0.7f) }) { onPick("original") }
        }
        items(packs, key = { it.packageName }) { pack ->
            Option("from ${pack.label}…", current?.startsWith("pack|${pack.packageName}|") == true, accent, preview = null) {
                onBrowse(pack)
            }
        }
        item {
            Option("choose an image…", current?.startsWith("image|") == true, accent, preview = null, onClick = onPickImage)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Option(
    label: String,
    selected: Boolean,
    accent: Color,
    preview: (@Composable (Modifier) -> Unit)?,
    onClick: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(noRipple, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        if (preview != null) {
            preview(Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
        }
        BasicText(label, style = MetroType.Large.copy(color = if (selected) accent else LocalMetroColors.current.foreground))
    }
}

@Composable
private fun Square(image: ImageBitmap, accent: Color, modifier: Modifier, fraction: Float = 0.62f) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.background(accent)) {
        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(fraction))
    }
}

@Composable
private fun ArtSquare(app: AppEntry, modifier: Modifier) {
    val art by produceState<ImageBitmap?>(null, app.key) {
        value = dev.metro.launcher.apps.TileArt.render(app, 168, 168)
    }
    Box(modifier.background(dev.metro.launcher.apps.TileArt.Fallback)) {
        art?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun PackGrid(app: AppEntry, pack: IconPackInfo, accent: Color, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val names by produceState(emptyList<String>(), pack.packageName) {
        value = withContext(Dispatchers.IO) { IconPacks.allDrawables(context, pack.packageName) }
    }
    var query by rememberSaveable(app.key) {
        mutableStateOf(app.label.lowercase().split(' ').firstOrNull().orEmpty())
    }
    val shown = remember(names, query) {
        val q = query.trim().lowercase().replace(' ', '_')
        (if (q.isEmpty()) names else names.filter { it.lowercase().contains(q) }).take(400)
    }
    val colors = LocalMetroColors.current
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(20.dp))
        BasicText(pack.label.uppercase(), style = MetroType.AppTitle)
        Spacer(Modifier.height(10.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MetroType.Large.copy(color = Color.Black, fontSize = 18.sp),
            cursorBrush = SolidColor(accent),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(2.dp, colors.foreground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        BasicText("${shown.size} of ${names.size} icons", style = MetroType.Subtle.copy(fontSize = 13.sp))
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it }) { name ->
                val spec = "pack|${pack.packageName}|$name"
                val image by produceState(CustomIcons.cached(spec), spec) {
                    if (value == null) value = CustomIcons.load(context, spec)
                }
                val noRipple = remember { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(4.dp)
                        .aspectRatio(1f)
                        .metroTilt()
                        .background(accent)
                        .clickable(noRipple, indication = null) { onPick(spec) },
                ) {
                    image?.let { Image(it, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.64f)) }
                }
            }
        }
    }
}
