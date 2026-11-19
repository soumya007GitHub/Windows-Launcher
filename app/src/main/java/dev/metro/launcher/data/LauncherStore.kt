package dev.metro.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import dev.metro.launcher.physics.TileSize
import dev.metro.launcher.ui.MetroAccent
import dev.metro.launcher.ui.MetroThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One pinned Start tile. [key] is the app's flattened ComponentName. */
data class PinnedTile(val key: String, val size: TileSize)

data class LauncherSettings(
    val accent: MetroAccent = MetroAccent.Cobalt,
    /** 4 = classic, 6 = WP8 GDR3 "show more tiles". */
    val columns: Int = 4,
    /** Tiles become windows onto a static background photo. */
    val wallpaperCutout: Boolean = true, // new installs start with transparent tiles over a built-in wallpaper
    val hideSystemNavigation: Boolean = true,
    /** WP8 curtain lockscreen (off until the user turns it on). */
    val lockscreenEnabled: Boolean = false,
    /** White Metro glyphs on tiles instead of the apps' colour icons (where available). */
    val metroIcons: Boolean = true,
    /** Package name of the chosen third-party icon pack, or null for none. */
    val iconPack: String? = null,
    /** Metro's search / start / back bar over every app (needs the Metro accessibility service). */
    val globalNavBar: Boolean = true,
    /** WP8.1 volume panel (needs the Metro accessibility service). */
    val volumePanel: Boolean = true,
    /** Lock / unlock sound effects. */
    val lockSounds: Boolean = true,
    /** WP8 Background: dark (black) or light (white) pages. */
    val theme: MetroThemeMode = MetroThemeMode.Dark,
)

/**
 * Start layout + theme, persisted in private SharedPreferences (small, synchronous, no extra dependency).
 * The background photo is copied into app-private storage so it survives the picker's URI grant expiring.
 */
class LauncherStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wallpaperFile = File(appContext.filesDir, WALLPAPER_FILE)

    private val _tiles = MutableStateFlow(readTiles())

    /** null until the Start screen has been seeded for the first time. */
    val tiles: StateFlow<List<PinnedTile>?> = _tiles.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    private val _wallpaperVersion = MutableStateFlow(if (wallpaperFile.exists()) wallpaperFile.lastModified() else 0L)

    /** Changes whenever the background photo changes; 0 = none. */
    val wallpaperVersion: StateFlow<Long> = _wallpaperVersion.asStateFlow()

    private val _iconOverrides = MutableStateFlow(readOverrides())

    /**
     * Per-app icon choice (app key -> spec): "glyph", "art", "original", "pack|<pkg>|<drawable>"
     * or "image|<file>". Missing = automatic.
     */
    val iconOverrides: StateFlow<Map<String, String>> = _iconOverrides.asStateFlow()

    fun setIconOverride(key: String, spec: String?) {
        val next = _iconOverrides.value.toMutableMap()
        val previous = next[key]
        if (spec == null) next.remove(key) else next[key] = spec
        _iconOverrides.value = next
        prefs.edit { putString(KEY_ICON_OVERRIDES, JSONObject(next as Map<*, *>).toString()) }
        // Drop a replaced custom image file.
        if (previous != null && previous != spec && previous.startsWith("image|")) {
            iconFile(previous.substringAfter('|')).delete()
        }
    }

    fun iconFile(name: String): File = File(File(appContext.filesDir, "icons"), name)

    /** Copies a user-picked image (downscaled to 512 px) into private storage; returns its spec. */
    suspend fun importIconImage(key: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodeSampled(uri, 512, 512) }.getOrNull() ?: return@withContext null
        val name = key.hashCode().toUInt().toString(16) + "_" + System.currentTimeMillis() + ".png"
        val file = iconFile(name)
        file.parentFile?.mkdirs()
        val ok = runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
        bitmap.recycle()
        if (ok) "image|$name" else null
    }

    private fun readOverrides(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(KEY_ICON_OVERRIDES, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun setTiles(tiles: List<PinnedTile>) {
        _tiles.value = tiles
        prefs.edit { putString(KEY_TILES, encode(tiles)) }
    }

    /** Pins at the end of Start, as WP does. Returns false if already pinned. */
    fun pin(key: String, size: TileSize = TileSize.Medium): Boolean {
        val current = _tiles.value.orEmpty()
        if (current.any { it.key == key }) return false
        setTiles(current + PinnedTile(key, size))
        return true
    }

    fun unpin(key: String) {
        setTiles(_tiles.value.orEmpty().filterNot { it.key == key })
    }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit {
            putString(KEY_ACCENT, next.accent.name)
            putInt(KEY_COLUMNS, next.columns)
            putBoolean(KEY_CUTOUT, next.wallpaperCutout)
            putBoolean(KEY_HIDE_NAV, next.hideSystemNavigation)
            putBoolean(KEY_LOCKSCREEN, next.lockscreenEnabled)
            putBoolean(KEY_METRO_ICONS, next.metroIcons)
            putString(KEY_ICON_PACK, next.iconPack)
            putBoolean(KEY_GLOBAL_NAV, next.globalNavBar)
            putBoolean(KEY_VOLUME_PANEL, next.volumePanel)
            putBoolean(KEY_LOCK_SOUNDS, next.lockSounds)
            putString(KEY_THEME, next.theme.name)
        }
    }

    /** Home-screen photo (behind transparent tiles): decoded at roughly screen size, private copy. */
    suspend fun importWallpaper(uri: Uri, maxWidth: Int, maxHeight: Int): Boolean =
        importPhoto(uri, maxWidth, maxHeight, wallpaperFile).also { if (it) _wallpaperVersion.value = System.currentTimeMillis() }

    suspend fun loadWallpaper(): ImageBitmap? = loadPhoto(wallpaperFile)

    fun hasWallpaper(): Boolean = wallpaperFile.exists()

    /** Uses one of Metro's built-in wallpapers (an asset path) as the home screen photo, or the lock screen photo. */
    suspend fun useBuiltInWallpaper(assetPath: String, forLock: Boolean): Boolean = withContext(Dispatchers.IO) {
        val target = if (forLock) lockWallpaperFile else wallpaperFile
        val tmp = File(appContext.filesDir, target.name + ".tmp")
        val ok = runCatching {
            appContext.assets.open(assetPath).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            tmp.delete()
            false
        }
        if (ok) {
            if (forLock) _lockWallpaperVersion.value = System.currentTimeMillis()
            else _wallpaperVersion.value = System.currentTimeMillis()
        }
        ok
    }

    private val lockWallpaperFile = File(appContext.filesDir, LOCK_WALLPAPER_FILE)
    private val _lockWallpaperVersion =
        MutableStateFlow(if (lockWallpaperFile.exists()) lockWallpaperFile.lastModified() else 0L)

    /** Changes whenever the lock-screen photo changes; 0 = none (the lock screen uses the home photo). */
    val lockWallpaperVersion: StateFlow<Long> = _lockWallpaperVersion.asStateFlow()

    /** Lock-screen photo, independent of the home-screen photo. */
    suspend fun importLockWallpaper(uri: Uri, maxWidth: Int, maxHeight: Int): Boolean =
        importPhoto(uri, maxWidth, maxHeight, lockWallpaperFile).also { if (it) _lockWallpaperVersion.value = System.currentTimeMillis() }

    /** The lock-screen photo, or the home photo when no separate one is set. */
    suspend fun loadLockWallpaper(): ImageBitmap? = loadPhoto(lockWallpaperFile) ?: loadWallpaper()

    fun clearLockWallpaper() {
        lockWallpaperFile.delete()
        _lockWallpaperVersion.value = 0L
    }

    private suspend fun importPhoto(uri: Uri, maxWidth: Int, maxHeight: Int, target: File): Boolean = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodeSampled(uri, maxWidth, maxHeight) }.getOrNull()
            ?: return@withContext false
        val tmp = File(appContext.filesDir, target.name + ".tmp")
        val written = runCatching {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrDefault(false)
        bitmap.recycle()
        if (!written) {
            tmp.delete()
            return@withContext false
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }

    private suspend fun loadPhoto(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }

    private fun decodeSampled(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        val resolver = appContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxWidth, maxHeight))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }

    private fun sampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) sample *= 2
        return sample
    }

    private fun readTiles(): List<PinnedTile>? {
        val raw = prefs.getString(KEY_TILES, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val size = runCatching { TileSize.valueOf(o.getString("s")) }.getOrNull() ?: return@mapNotNull null
                PinnedTile(o.getString("k"), size)
            }
        }.getOrNull()
    }

    private fun encode(tiles: List<PinnedTile>): String {
        val array = JSONArray()
        tiles.forEach { array.put(JSONObject().put("k", it.key).put("s", it.size.name)) }
        return array.toString()
    }

    private fun readSettings(): LauncherSettings {
        val defaults = LauncherSettings()
        return LauncherSettings(
            accent = prefs.getString(KEY_ACCENT, null)
                ?.let { name -> MetroAccent.entries.firstOrNull { it.name == name } }
                ?: defaults.accent,
            columns = prefs.getInt(KEY_COLUMNS, defaults.columns).let { if (it == 6) 6 else 4 },
            wallpaperCutout = prefs.getBoolean(KEY_CUTOUT, defaults.wallpaperCutout),
            hideSystemNavigation = prefs.getBoolean(KEY_HIDE_NAV, defaults.hideSystemNavigation),
            lockscreenEnabled = prefs.getBoolean(KEY_LOCKSCREEN, defaults.lockscreenEnabled),
            metroIcons = prefs.getBoolean(KEY_METRO_ICONS, defaults.metroIcons),
            iconPack = prefs.getString(KEY_ICON_PACK, null),
            globalNavBar = prefs.getBoolean(KEY_GLOBAL_NAV, defaults.globalNavBar),
            volumePanel = prefs.getBoolean(KEY_VOLUME_PANEL, defaults.volumePanel),
            lockSounds = prefs.getBoolean(KEY_LOCK_SOUNDS, defaults.lockSounds),
            theme = prefs.getString(KEY_THEME, null)?.let { name -> MetroThemeMode.entries.firstOrNull { it.name == name } } ?: defaults.theme,
        )
    }

    companion object {
        private const val PREFS = "metro_launcher"
        private const val KEY_TILES = "start_tiles"
        private const val KEY_ACCENT = "accent"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_CUTOUT = "wallpaper_cutout"
        private const val KEY_HIDE_NAV = "hide_system_nav"
        private const val KEY_LOCKSCREEN = "lockscreen_enabled"
        private const val KEY_METRO_ICONS = "metro_icons"
        private const val KEY_ICON_PACK = "icon_pack"
        private const val KEY_GLOBAL_NAV = "global_nav_bar"
        private const val KEY_VOLUME_PANEL = "volume_panel"
        private const val KEY_LOCK_SOUNDS = "lock_sounds"
        private const val KEY_THEME = "theme"
        private const val KEY_ICON_OVERRIDES = "icon_overrides"
        private const val WALLPAPER_FILE = "start_background.jpg"
        private const val LOCK_WALLPAPER_FILE = "lock_background.jpg"

        @Volatile
        private var instance: LauncherStore? = null

        fun get(context: Context): LauncherStore =
            instance ?: synchronized(this) { instance ?: LauncherStore(context).also { instance = it } }
    }
}
