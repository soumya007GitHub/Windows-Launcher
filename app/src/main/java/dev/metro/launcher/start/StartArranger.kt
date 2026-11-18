package dev.metro.launcher.start

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import dev.metro.launcher.apps.AppEntry
import dev.metro.launcher.data.PinnedTile
import dev.metro.launcher.physics.TileSize
import dev.metro.launcher.recents.RecentApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a designed Start screen from the user's own apps, in the rhythm of a well-kept WP8 Start:
 * two medium "people" tiles, a wide feature tile, a row of four smalls, a medium beside four smalls,
 * wide games, and so on. Apps are ranked by how much they were actually used over the last two
 * weeks (with Usage access), so the most-used apps get the big tiles. Games go on wide or medium
 * tiles, where their full artwork looks best. Nothing leaves the device.
 */
object StartArranger {

    private enum class Role { Dialer, Messages, Camera, Gallery, Browser, Settings, Feature, Top, Game }

    /** One slot of the layout rhythm (first-fit packing turns it into the grid). */
    private data class Slot(val role: Role, val size: TileSize)

    private val Rhythm = listOf(
        Slot(Role.Dialer, TileSize.Medium), Slot(Role.Messages, TileSize.Medium),
        Slot(Role.Feature, TileSize.Wide),
        Slot(Role.Camera, TileSize.Small), Slot(Role.Gallery, TileSize.Small),
        Slot(Role.Browser, TileSize.Small), Slot(Role.Settings, TileSize.Small),
        Slot(Role.Top, TileSize.Medium),
        Slot(Role.Top, TileSize.Small), Slot(Role.Top, TileSize.Small),
        Slot(Role.Top, TileSize.Small), Slot(Role.Top, TileSize.Small),
        Slot(Role.Game, TileSize.Wide),
        Slot(Role.Top, TileSize.Medium), Slot(Role.Top, TileSize.Medium),
        Slot(Role.Top, TileSize.Small), Slot(Role.Top, TileSize.Small),
        Slot(Role.Top, TileSize.Small), Slot(Role.Top, TileSize.Small),
        Slot(Role.Game, TileSize.Medium), Slot(Role.Top, TileSize.Medium),
        Slot(Role.Top, TileSize.Wide),
    )

    suspend fun arrange(context: Context, apps: List<AppEntry>): List<PinnedTile> = withContext(Dispatchers.Default) {
        if (apps.isEmpty()) return@withContext emptyList()
        val byPackage = apps.groupBy { it.packageName }
        val pm = context.packageManager

        val defaults = mapOf(
            Role.Dialer to Intent(Intent.ACTION_DIAL),
            Role.Messages to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Role.Camera to Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Role.Gallery to Intent(Intent.ACTION_VIEW).setType("image/*"),
            Role.Browser to Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Role.Settings to Intent(Settings.ACTION_SETTINGS),
        ).mapValues { (_, intent) -> resolve(pm, intent)?.let { byPackage[it]?.firstOrNull() } }

        val ranked = rankByUse(context, apps)
        val used = HashSet<String>()
        defaults.values.filterNotNull().forEach { used += it.key }
        val games = ranked.filter { isGame(pm, it.packageName) }

        fun nextTop(): AppEntry? = ranked.firstOrNull { it.key !in used }
        fun nextGame(): AppEntry? = games.firstOrNull { it.key !in used }

        val result = ArrayList<PinnedTile>()
        for (slot in Rhythm) {
            val app = when (slot.role) {
                Role.Feature, Role.Top -> nextTop()
                Role.Game -> nextGame() ?: nextTop()
                else -> defaults[slot.role]?.takeIf { it.key !in used || result.none { p -> p.key == it.key } }
                    ?: nextTop()
            } ?: continue
            if (result.any { it.key == app.key }) continue
            used += app.key
            result += PinnedTile(app.key, slot.size)
        }
        result
    }

    /** Most-used first (foreground time over 14 days); alphabetical when Usage access is off. */
    private fun rankByUse(context: Context, apps: List<AppEntry>): List<AppEntry> {
        if (!RecentApps.hasAccess(context)) return apps
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val stats = runCatching { usage.queryAndAggregateUsageStats(end - 14L * 24 * 60 * 60 * 1000, end) }
            .getOrNull().orEmpty()
        val time = stats.mapValues { it.value.totalTimeInForeground }
        return apps
            .filter { it.packageName != context.packageName }
            .sortedWith(compareByDescending<AppEntry> { time[it.packageName] ?: 0L }.thenBy { it.label.lowercase() })
    }

    private fun isGame(pm: PackageManager, pkg: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(pkg, 0)
        info.category == ApplicationInfo.CATEGORY_GAME
    }.getOrDefault(false)

    private fun resolve(pm: PackageManager, intent: Intent): String? {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return info?.activityInfo?.packageName?.takeIf { it != "android" }
    }
}
