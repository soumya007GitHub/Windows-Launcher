package dev.metro.launcher.apps

import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.metro.launcher.data.LauncherStore
import dev.metro.launcher.data.PinnedTile
import dev.metro.launcher.physics.TileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    /** White monochrome glyph for Metro tiles, or null when the app ships nothing usable. */
    val glyph: ImageBitmap? = null,
    /** Icon from the selected icon pack, if the pack covers this app. */
    val packIcon: ImageBitmap? = null,
    /** The launcher icon itself, kept so tiles can render full-bleed artwork at their real size. */
    val drawable: Drawable? = null,
) {
    val packageName: String get() = component.packageName

    /** What to draw on an accent square: icon pack first, then the Metro glyph, then the app's own icon. */
    fun tileIcon(metroIcons: Boolean): ImageBitmap =
        packIcon ?: if (metroIcons) glyph ?: icon else icon
}

/**
 * Launchable apps for the current user, kept fresh through [LauncherApps.Callback]
 * (install, uninstall, update, SD-card availability).
 */
class AppRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)

    /** A→Z; null while the first load is running. */
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refresh()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = refresh()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = refresh()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
    }

    /** Package of the selected icon pack, or null. */
    private var iconPack: String? = LauncherStore.get(appContext).settings.value.iconPack

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun setIconPack(packPackage: String?) {
        if (packPackage == iconPack) return
        iconPack = packPackage
        refresh()
    }

    /** Debounced reload; an update usually fires several callbacks in a row. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (_apps.value != null) delay(250)
            _apps.value = load()
        }
    }

    private suspend fun load(): List<AppEntry> = withContext(Dispatchers.IO) {
        val density = appContext.resources.displayMetrics.density
        val iconPx = (48 * density).toInt()
        val glyphPx = (72 * density).toInt()
        val pack = iconPack?.let { IconPacks.load(appContext, it) }
        launcherApps.getActivityList(null, Process.myUserHandle())
            .asSequence()
            .filter { it.componentName.packageName != appContext.packageName }
            .map { info ->
                val drawable = info.getIcon(0)
                AppEntry(
                    key = info.componentName.flattenToShortString(),
                    label = info.label.toString(),
                    component = info.componentName,
                    user = info.user,
                    icon = drawable.toBitmap(iconPx, iconPx).asImageBitmap(),
                    glyph = runCatching { MetroGlyphs.render(drawable, glyphPx) }.getOrNull(),
                    drawable = drawable,
                    packIcon = pack?.drawableFor(info.componentName)
                        ?.let { runCatching { it.toBitmap(glyphPx, glyphPx).asImageBitmap() }.getOrNull() },
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null) {
        // The launcher already played its turnstile; the app should just appear.
        val options = ActivityOptions.makeCustomAnimation(appContext, android.R.anim.fade_in, 0).toBundle()
        launcherApps.startMainActivity(app.component, app.user, sourceBounds, options)
    }

    /** Starts the app with no window animation at all (used for one-tap screen-off apps). */
    fun launchInstantly(app: AppEntry) {
        val options = ActivityOptions.makeCustomAnimation(appContext, 0, 0).toBundle()
        launcherApps.startMainActivity(app.component, app.user, null, options)
    }

    fun openAppInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }

    /** Opens the system uninstall confirmation; the user decides there. */
    fun uninstall(app: AppEntry) {
        appContext.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun webSearch(query: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(search)
        } catch (_: ActivityNotFoundException) {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** First-run Start screen: the phone's default dialer, messages, camera, gallery, browser, settings, then A→Z filler. */
    fun defaultPins(apps: List<AppEntry>): List<PinnedTile> {
        val byPackage = apps.groupBy { it.packageName }
        val wanted = listOf(
            Intent(Intent.ACTION_DIAL) to TileSize.Medium,
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) to TileSize.Medium,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE) to TileSize.Small,
            Intent(Intent.ACTION_VIEW).setType("image/*") to TileSize.Small,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")) to TileSize.Small,
            Intent(Settings.ACTION_SETTINGS) to TileSize.Small,
        )
        val result = LinkedHashMap<String, PinnedTile>()
        for ((intent, size) in wanted) {
            val pkg = resolvePackage(intent) ?: continue
            val app = byPackage[pkg]?.firstOrNull() ?: continue
            result.putIfAbsent(app.key, PinnedTile(app.key, size))
        }
        val filler = listOf(TileSize.Wide, TileSize.Small, TileSize.Small, TileSize.Medium, TileSize.Small, TileSize.Small)
        var i = 0
        for (app in apps) {
            if (result.size >= 14) break
            if (app.key in result) continue
            result[app.key] = PinnedTile(app.key, filler[i++ % filler.size])
        }
        return result.values.toList()
    }

    private fun resolvePackage(intent: Intent): String? {
        val pm = appContext.packageManager
        val info: ResolveInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        // "android" = the chooser, i.e. no default set.
        return info?.activityInfo?.packageName?.takeIf { it != "android" }
    }

    companion object {
        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) { instance ?: AppRepository(context).also { instance = it } }
    }
}
