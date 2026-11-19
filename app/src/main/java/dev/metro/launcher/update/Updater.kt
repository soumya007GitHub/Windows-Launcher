package dev.metro.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer version published on the project's GitHub Releases page. */
data class Release(val version: String, val apkUrl: String, val apkSize: Long)

/**
 * Checks GitHub Releases for a newer Metro, downloads its APK and hands it to Android's installer.
 * Only public release data is requested; nothing about the phone or the user is sent. Android
 * always shows its own install confirmation, and refuses an APK not signed with the same key.
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/soumya007GitHub/Windows-Launcher/releases/latest"
    private const val USER_AGENT = "MetroLauncher-Updater"
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The latest release if it is newer than the installed version, null if up to date. Throws on network errors. */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val json = try {
            if (connection.responseCode != 200) error("GitHub answered ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val release = JSONObject(json)
        val version = release.getString("tag_name").removePrefix("v").removePrefix("V")
        if (compare(version, installedVersion(context)) <= 0) return@withContext null
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
            ?: error("the release has no APK")
        Release(version, apk.getString("browser_download_url"), apk.optLong("size", -1L))
    }

    /** Downloads the release APK to private storage, reporting progress 0..1. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(release.apkUrl.startsWith("https://github.com/") ||
                release.apkUrl.startsWith("https://objects.githubusercontent.com/")) { "unexpected download address" }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only ever keep the one being installed
            val target = File(dir, "MetroLauncher-${release.version}.apk")
            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode != 200) error("download failed (${connection.responseCode})")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done > MAX_APK_BYTES) error("download too large")
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            } finally {
                connection.disconnect()
            }
            verify(context, target)
            target
        }

    /** The file must be a Metro APK with a higher version code than the one installed. */
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: run { apk.delete(); error("the download is not a valid app") }
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, 0)
        val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode else installed.versionCode.toLong()
        if (info.packageName != context.packageName || newCode <= oldCode) {
            apk.delete()
            error("the download is not a newer Metro")
        }
    }

    /** Android needs "Install unknown apps" allowed for Metro before it can install an update. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the APK to Android's package installer; Android asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("metro.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /** Compares dotted versions numerically: 1.10.0 > 1.9.2. */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

/** Receives the installer's result; when Android needs the user to confirm, shows its dialog. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            else -> UpdateStatus.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/** Lets the settings page show an install failure reported by [UpdateInstallReceiver]. */
object UpdateStatus {
    val lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun failed(message: String?) {
        lastError.value = message ?: "Android did not install the update"
    }
}
