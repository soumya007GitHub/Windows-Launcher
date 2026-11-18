package dev.metro.launcher.apps

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class IconPackInfo(val packageName: String, val label: String)

/**
 * Third-party icon packs in the ADW / Nova / Apex / GO format used by nearly every pack on the
 * Play Store: the pack exposes an `appfilter.xml` mapping app components to drawable names.
 */
object IconPacks {

    private val THEME_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.dlto.atom.launcher.THEME",
    )
    private const val APEX_CATEGORY = "com.anddoes.launcher.THEME"

    fun installed(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val intents = THEME_ACTIONS.map { Intent(it) } +
            Intent(Intent.ACTION_MAIN).addCategory(APEX_CATEGORY)
        return intents
            .flatMap { query(pm, it) }
            .map { it.activityInfo.packageName to it }
            .distinctBy { it.first }
            .map { (pkg, info) -> IconPackInfo(pkg, info.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    /** A parsed pack: component → drawable name, plus the pack's resources. */
    class Loaded internal constructor(
        private val resources: Resources,
        private val packageName: String,
        private val byComponent: Map<String, String>,
        private val byPackage: Map<String, String>,
    ) {
        @SuppressLint("DiscouragedApi") // icon packs are addressed by name; there is no other API
        fun drawableFor(component: ComponentName): Drawable? {
            val name = byComponent["${component.packageName}/${component.className}"]
                ?: byPackage[component.packageName]
                ?: return null
            val id = resources.getIdentifier(name, "drawable", packageName)
            if (id == 0) return null
            return runCatching { ResourcesCompat.getDrawable(resources, id, null) }.getOrNull()
        }
    }

    @SuppressLint("DiscouragedApi")
    fun load(context: Context, packPackage: String): Loaded? = runCatching {
        val resources = context.packageManager.getResourcesForApplication(packPackage)
        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()

        fun read(parser: XmlPullParser) {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    val parsed = component?.let(::parseComponent)
                    if (parsed != null && !drawable.isNullOrBlank()) {
                        byComponent.putIfAbsent(parsed.first + "/" + parsed.second, drawable)
                        byPackage.putIfAbsent(parsed.first, drawable)
                    }
                }
                event = parser.next()
            }
        }

        val xmlId = resources.getIdentifier("appfilter", "xml", packPackage)
        if (xmlId != 0) {
            resources.getXml(xmlId).use { read(it) }
        } else {
            resources.assets.open("appfilter.xml").use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(stream, "UTF-8")
                read(parser)
            }
        }
        if (byComponent.isEmpty()) null else Loaded(resources, packPackage, byComponent, byPackage)
    }.getOrNull()

    /** Loads one drawable of a pack by name (used by the per-tile icon picker). */
    @SuppressLint("DiscouragedApi")
    fun loadDrawable(context: Context, packPackage: String, name: String): Drawable? = runCatching {
        val resources = context.packageManager.getResourcesForApplication(packPackage)
        val id = resources.getIdentifier(name, "drawable", packPackage)
        if (id == 0) null else ResourcesCompat.getDrawable(resources, id, null)
    }.getOrNull()

    /**
     * Every icon a pack offers: its `drawable.xml` catalogue when present, otherwise every drawable
     * named in `appfilter.xml`.
     */
    @SuppressLint("DiscouragedApi")
    fun allDrawables(context: Context, packPackage: String): List<String> = runCatching {
        val resources = context.packageManager.getResourcesForApplication(packPackage)
        val names = LinkedHashSet<String>()

        fun read(parser: XmlPullParser) {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let(names::add)
                }
                event = parser.next()
            }
        }

        fun readXml(file: String): Boolean {
            val id = resources.getIdentifier(file, "xml", packPackage)
            if (id != 0) {
                resources.getXml(id).use { read(it) }
                return true
            }
            return runCatching {
                resources.assets.open("$file.xml").use { stream ->
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(stream, "UTF-8")
                    read(parser)
                }
                true
            }.getOrDefault(false)
        }

        if (!readXml("drawable") || names.isEmpty()) readXml("appfilter")
        names.toList()
    }.getOrDefault(emptyList())

    /** "ComponentInfo{com.app/com.app.Main}" → ("com.app", "com.app.Main"); expands ".Main". */
    private fun parseComponent(raw: String): Pair<String, String>? {
        val inner = raw.substringAfter('{', "").substringBefore('}', "")
        val pkg = inner.substringBefore('/', "")
        var cls = inner.substringAfter('/', "")
        if (pkg.isBlank() || cls.isBlank()) return null
        if (cls.startsWith('.')) cls = pkg + cls
        return pkg to cls
    }
}
