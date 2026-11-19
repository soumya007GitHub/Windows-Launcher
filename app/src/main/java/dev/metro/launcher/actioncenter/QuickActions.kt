package dev.metro.launcher.actioncenter

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The Action Center's quick actions: the same set Android's own quick settings offer on this phone
 * ([spec] is Android's tile name), plus a few WP 8.1 ones.
 */
enum class QuickAction(val label: String, val spec: String?) {
    Wifi("Wi-Fi", "wifi"),
    Bluetooth("Bluetooth", "bt"),
    Cellular("cellular", "cell"),
    Airplane("airplane mode", "airplane"),
    Rotation("rotation lock", "rotation"),
    Flashlight("flashlight", "flashlight"),
    QuietHours("quiet hours", "dnd"),
    Sound("ringer", "ring_vibrate_silent"),
    Location("location", "location"),
    Hotspot("hotspot", "hotspot"),
    BatterySaver("battery saver", "battery"),
    DarkMode("dark mode", "dark"),
    EyeComfort("eye comfort", "night"),
    Brightness("brightness", null),
    ReduceBrightness("extra dim", "reduce_brightness"),
    FontSize("font size", "font_scaling"),
    ScreenRecord("screen record", "screen_record"),
    Screenshot("screenshot", null),
    SplitScreen("split screen", "split_screen"),
    Scanner("scanner", "scanner"),
    Camera("camera", null),
    Nfc("NFC", "nfc"),
    Cast("cast", "cast"),
    PowerOff("power", "power_off"),
    Lock("lock", null),
}

/** Brightness steps the brightness action cycles through, like WP 8.1. */
enum class BrightnessLevel(val label: String, val value: Int) { Auto("auto", -1), Low("low", 40), Medium("medium", 128), High("high", 255) }

/**
 * Reads and changes quick actions. What a normal app may change differs by setting: flashlight,
 * rotation lock, brightness (after "Modify system settings" is allowed once), quiet hours and the
 * ringer switch directly; screenshot, split screen, power and lock go through the Metro service.
 * Android doesn't let apps switch the rest (Wi-Fi, Bluetooth, data, airplane mode...), so those
 * open Android's own panel or page, while their tiles still show the live state.
 */
class QuickActions(private val context: Context, private val service: AccessibilityService? = null) {

    private val camera = context.getSystemService(CameraManager::class.java)
    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull { id ->
            camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    var torchOn by mutableStateOf(false)
        private set

    /** Bumped after every change so the panel re-reads states. */
    var revision by mutableIntStateOf(0)
        private set

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) torchOn = enabled
        }
    }

    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy else headset = proxy
            revision++
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null else headset = null
        }
    }

    init {
        runCatching { camera.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper())) }
        if (canReadBluetooth()) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            runCatching { adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP) }
            runCatching { adapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET) }
        }
    }

    fun release() {
        runCatching { camera.unregisterTorchCallback(torchCallback) }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        a2dp?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headset?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
    }

    fun refresh() {
        revision++
    }

    // ------------------------------------------------------------------ availability

    /** Android's quick settings tiles on this phone, if Android lets Metro read the list. */
    private val phoneTiles: Set<String>? = runCatching {
        Settings.Secure.getString(context.contentResolver, "sysui_qs_tiles")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    }.getOrNull()

    fun isAvailable(action: QuickAction): Boolean = when (action) {
        QuickAction.Flashlight -> torchId != null
        QuickAction.Nfc -> runCatching { android.nfc.NfcAdapter.getDefaultAdapter(context) != null }.getOrDefault(false)
        QuickAction.Screenshot, QuickAction.SplitScreen, QuickAction.PowerOff, QuickAction.Lock -> service != null || true
        else -> true
    }

    /** What the settings page offers: the actions matching this phone's own quick settings first. */
    fun offered(): List<QuickAction> {
        val all = QuickAction.entries.filter { isAvailable(it) }
        val tiles = phoneTiles ?: return all
        val aliases = mapOf("cellular" to "cell", "offline" to "airplane", "eye_mode" to "night", "night_mode" to "dark", "bluetooth" to "bt")
        val specs = tiles.map { aliases[it] ?: it }.toSet()
        val onPhone = all.filter { it.spec != null && it.spec in specs }
        return onPhone + (all - onPhone.toSet())
    }

    // ------------------------------------------------------------------ state

    fun isOn(action: QuickAction): Boolean = runCatching {
        when (action) {
            QuickAction.Wifi -> context.getSystemService(WifiManager::class.java).isWifiEnabled
            QuickAction.Bluetooth -> context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
            QuickAction.Flashlight -> torchOn
            QuickAction.Rotation -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
            QuickAction.Brightness -> brightness() != BrightnessLevel.Auto
            QuickAction.QuietHours -> context.getSystemService(NotificationManager::class.java).currentInterruptionFilter >
                NotificationManager.INTERRUPTION_FILTER_ALL
            QuickAction.Sound -> context.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL
            QuickAction.Airplane -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            QuickAction.Cellular -> Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1 && simReady()
            QuickAction.Location -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            }
            QuickAction.BatterySaver -> context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
            QuickAction.DarkMode -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            QuickAction.Nfc -> android.nfc.NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
            else -> false
        }
    }.getOrDefault(false)

    /** Text under the icon, as WP: the network or device name when there is one. */
    fun label(action: QuickAction): String = when (action) {
        QuickAction.Wifi -> when {
            !isOn(action) -> "Wi-Fi"
            else -> wifiName() ?: if (wifiConnected()) "connected" else "not connected"
        }
        QuickAction.Bluetooth -> when {
            !isOn(action) -> "Bluetooth"
            else -> bluetoothDevice() ?: "not connected"
        }
        QuickAction.Cellular -> carrier() ?: "cellular"
        QuickAction.Brightness -> brightness().label
        QuickAction.Sound -> when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ringer"
        }
        else -> action.label
    }

    fun brightness(): BrightnessLevel {
        val resolver = context.contentResolver
        val auto = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        if (auto) return BrightnessLevel.Auto
        val value = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return when {
            value < 85 -> BrightnessLevel.Low
            value < 190 -> BrightnessLevel.Medium
            else -> BrightnessLevel.High
        }
    }

    // ------------------------------------------------------------------ brightness slider

    fun isAutoBrightness(): Boolean = Settings.System.getInt(
        context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0,
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    /** Screen brightness 0..1 (Android's 0..255 setting). */
    fun brightnessFraction(): Float =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    fun canChangeBrightness(): Boolean = Settings.System.canWrite(context)

    /** The one-time "Modify system settings" page for Metro. */
    fun writeSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Moving the slider switches auto off, as Android's own slider does. */
    fun setBrightness(fraction: Float) {
        if (!canChangeBrightness()) return
        runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (fraction.coerceIn(0f, 1f) * 254).toInt() + 1)
        }
        revision++
    }

    fun setAutoBrightness(on: Boolean) {
        if (!canChangeBrightness()) return
        runCatching {
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        revision++
    }

    // ------------------------------------------------------------------ network info

    fun canReadWifiName(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canReadBluetooth(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun wifiConnected(): Boolean {
        val viaConnectivity = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(false)
        if (viaConnectivity) return true
        // Fallback: Wi-Fi reports an associated network even when its name is hidden (location off).
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.getSystemService(WifiManager::class.java).connectionInfo
            info != null && info.networkId != -1 && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED
        }.getOrDefault(false)
    }

    /** Connected Wi-Fi network name; Android only gives it with location permission and location on. */
    fun wifiName(): String? = runCatching {
        if (!canReadWifiName()) return null
        @Suppress("DEPRECATION")
        val info: WifiInfo? = context.getSystemService(WifiManager::class.java).connectionInfo
        info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('<', '>') && !it.contains("unknown ssid") }
    }.getOrNull()

    fun bluetoothDevice(): String? = runCatching {
        if (!canReadBluetooth()) return null
        val devices = (a2dp?.connectedDevices.orEmpty() + headset?.connectedDevices.orEmpty())
        devices.firstOrNull()?.name
    }.getOrNull()

    private fun simReady(): Boolean = runCatching {
        context.getSystemService(TelephonyManager::class.java).simState == TelephonyManager.SIM_STATE_READY
    }.getOrDefault(false)

    /** Mobile network name (Jio, Airtel...), or "No SIM". */
    fun carrier(): String? = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return "No SIM"
        tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Mobile signal 0..4, or null without a SIM. */
    fun signalLevel(): Int? = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm.signalStrength?.level else 4
    }.getOrNull()

    // ------------------------------------------------------------------ actions

    /**
     * Toggles [action]. Returns an intent to open instead when Android needs the user (a system
     * panel, or a one-time permission page); the caller closes the Action Center and opens it.
     */
    fun toggle(action: QuickAction): Intent? {
        val result: Intent? = when (action) {
            QuickAction.Wifi -> panel(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            QuickAction.Bluetooth -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            QuickAction.Cellular, QuickAction.Airplane ->
                panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            QuickAction.Nfc -> panel(Settings.Panel.ACTION_NFC, Settings.ACTION_NFC_SETTINGS)
            QuickAction.Location -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            QuickAction.Hotspot -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            QuickAction.BatterySaver -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            QuickAction.DarkMode, QuickAction.EyeComfort -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            QuickAction.ReduceBrightness -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            QuickAction.FontSize -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            QuickAction.Cast -> Intent(Settings.ACTION_CAST_SETTINGS)
            QuickAction.Camera -> Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            QuickAction.Scanner -> Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            QuickAction.ScreenRecord -> null.also { service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
            QuickAction.Screenshot -> null.also {
                // Taken after the Action Center has gone (the caller closes it first).
                Handler(Looper.getMainLooper()).postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                }, 400)
            }
            QuickAction.SplitScreen -> null.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            }
            QuickAction.PowerOff -> null.also { service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) }
            QuickAction.Lock -> null.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            QuickAction.Flashlight -> null.also { torchId?.let { id -> runCatching { camera.setTorchMode(id, !torchOn) } } }
            QuickAction.Rotation -> writeSystem {
                val locked = isOn(QuickAction.Rotation)
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (locked) 1 else 0)
            }
            QuickAction.Brightness -> writeSystem {
                val levels = BrightnessLevel.entries
                val next = levels[(levels.indexOf(brightness()) + 1) % levels.size]
                val resolver = context.contentResolver
                if (next == BrightnessLevel.Auto) {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                } else {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next.value)
                }
            }
            QuickAction.QuietHours -> dnd {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.setInterruptionFilter(
                    if (isOn(QuickAction.QuietHours)) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                )
            }
            QuickAction.Sound -> {
                val audio = context.getSystemService(AudioManager::class.java)
                val next = when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                if (next == AudioManager.RINGER_MODE_SILENT || audio.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    dnd { audio.ringerMode = next }
                } else {
                    runCatching { audio.ringerMode = next }
                    null
                }
            }
        }
        refresh()
        return result?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True for actions that do their thing after the Action Center closes. */
    fun closesPanel(action: QuickAction): Boolean = action in setOf(
        QuickAction.Screenshot, QuickAction.SplitScreen, QuickAction.PowerOff, QuickAction.Lock, QuickAction.ScreenRecord,
    )

    /** Long-press: the full Android settings page for the action. */
    fun settingsIntent(action: QuickAction): Intent = Intent(
        when (action) {
            QuickAction.Wifi -> Settings.ACTION_WIFI_SETTINGS
            QuickAction.Bluetooth -> Settings.ACTION_BLUETOOTH_SETTINGS
            QuickAction.Cellular -> Settings.ACTION_DATA_ROAMING_SETTINGS
            QuickAction.Airplane -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            QuickAction.Location -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            QuickAction.Hotspot -> Settings.ACTION_WIRELESS_SETTINGS
            QuickAction.BatterySaver -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            QuickAction.QuietHours -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            QuickAction.Sound -> Settings.ACTION_SOUND_SETTINGS
            QuickAction.Nfc -> Settings.ACTION_NFC_SETTINGS
            QuickAction.Cast -> Settings.ACTION_CAST_SETTINGS
            QuickAction.ReduceBrightness -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_DISPLAY_SETTINGS
        },
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun panel(panelAction: String, fallback: String): Intent =
        Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) panelAction else fallback)

    private fun writeSystem(change: () -> Unit): Intent? {
        if (!Settings.System.canWrite(context)) {
            // One-time: "Modify system settings" for Metro.
            return Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { change() }
        return null
    }

    private fun dnd(change: () -> Unit): Intent? {
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            return Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        runCatching { change() }
        return null
    }
}
