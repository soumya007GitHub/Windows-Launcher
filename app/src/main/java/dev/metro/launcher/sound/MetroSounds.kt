package dev.metro.launcher.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.metro.launcher.data.LauncherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Lock / unlock sound effects. The built-in ones are the Windows Phone lock and unlock sounds
 * bundled with the app (res/raw), so they are there on every install. The user can replace either
 * with their own audio file. They play on every lock and unlock while "Lock & unlock sounds" is on,
 * whatever the ringer mode, at the media volume.
 */
object MetroSounds {

    enum class Kind(val fileName: String, val raw: Int) {
        Lock("sound_lock.audio", dev.metro.launcher.R.raw.wp_lock),
        Unlock("sound_unlock.audio", dev.metro.launcher.R.raw.wp_unlock),
    }

    private const val SAMPLE_RATE = 44_100
    private const val MAX_CUSTOM_BYTES = 2L * 1024 * 1024

    // The alarm channel: its level is set to Metro's own "lock sound volume" just for the sound
    // (see [withOwnVolume]), so media, ringer and notification volume don't change how loud it is.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val tracks = HashMap<Kind, AudioTrack>()

    /** Plays on every lock / unlock while the "Lock & unlock sounds" setting is on. */
    fun play(context: Context, kind: Kind) {
        if (!LauncherStore.get(context).settings.value.lockSounds) return
        // Only in ring mode: vibrate and silent play nothing.
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        preview(context, kind)
    }

    /** Plays regardless of the switch and ringer (the settings page), at Metro's own level. */
    fun preview(context: Context, kind: Kind) {
        withOwnVolume(context.applicationContext) {
            val custom = customFile(context, kind)
            if (custom.exists()) playCustom(context, kind, custom) else playBundled(context.applicationContext, kind)
        }
    }

    // ------------------------------------------------------------------ own volume

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The alarm volume to put back, while a lock sound has it raised; null otherwise. */
    private var savedAlarm: Int? = null
    private val restoreAlarm = Runnable {
        val saved = savedAlarm ?: return@Runnable
        savedAlarm = null
        runCatching { audioOf?.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
    }
    private var audioOf: AudioManager? = null

    /**
     * Sets the alarm channel to the lock-sound level for just over a second, plays, then puts the
     * user's alarm volume back. The sounds are well under a second long.
     */
    private fun withOwnVolume(context: Context, play: () -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        audioOf = audio
        val level = LauncherStore.get(context).settings.value.lockSoundLevel.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = ((level / 100f) * max).toInt().coerceIn(if (level > 0) 1 else 0, max)
        handler.removeCallbacks(restoreAlarm)
        if (savedAlarm == null) savedAlarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
        play()
        handler.postDelayed(restoreAlarm, RESTORE_AFTER_MS)
    }

    private const val RESTORE_AFTER_MS = 1_400L

    fun hasCustom(context: Context, kind: Kind): Boolean = customFile(context, kind).exists()

    fun resetCustom(context: Context) {
        Kind.entries.forEach { customFile(context, it).delete() }
    }

    /** Copies a user-picked audio file into private storage (2 MB cap: these are short effects). */
    suspend fun importCustom(context: Context, kind: Kind, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = customFile(context, kind)
            val tmp = File(target.parentFile, target.name + ".tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_CUSTOM_BYTES) error("too large")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("unreadable")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse {
            File(customFile(context, kind).path + ".tmp").delete()
            false
        }
    }

    private fun customFile(context: Context, kind: Kind) = File(context.filesDir, kind.fileName)

    /**
     * Custom sounds are decoded once to PCM and kept in a static AudioTrack, so playing them is
     * instant (decoding an MP3 on every lock / unlock caused a 1-2 s delay). While decoding, the
     * audio is normalised to full scale and given a soft-clipped boost of [CUSTOM_GAIN], which
     * makes short effects louder than SoundPool's 1.0 volume ceiling allows, without harsh clipping.
     */
    private const val CUSTOM_GAIN = 1.8
    private const val MAX_DECODED_BYTES = 8 * 1024 * 1024

    private class Decoded(val stamp: Long, val track: AudioTrack)

    private val customTracks = HashMap<Kind, Decoded>()
    private val decoding = HashSet<Kind>()

    /** Decodes the sounds ahead of time on a background thread (lock service / curtain start). */
    fun preload(context: Context) {
        Kind.entries.forEach { ensureDecoded(context.applicationContext, it) }
        Kind.entries.forEach { ensureBundled(context.applicationContext, it) }
    }

    // ------------------------------------------------------------------ bundled WP sounds

    private val bundledTracks = HashMap<Kind, AudioTrack>()
    private val bundledDecoding = HashSet<Kind>()

    private fun ensureBundled(context: Context, kind: Kind) {
        synchronized(this) {
            if (bundledTracks.containsKey(kind) || !bundledDecoding.add(kind)) return
        }
        Thread {
            val track = runCatching {
                context.resources.openRawResourceFd(kind.raw).use { fd ->
                    decodeToTrack { it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                }
            }.getOrNull()
            synchronized(this) {
                bundledDecoding -= kind
                if (track != null) bundledTracks[kind] = track
            }
        }.apply { isDaemon = true; name = "MetroSounds-bundled" }.start()
    }

    private fun playBundled(context: Context, kind: Kind) {
        val track = synchronized(this) { bundledTracks[kind] }
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.reloadStaticData()
                track.play()
            }
            return
        }
        ensureBundled(context, kind)
        // First play before decoding finished: play the file directly once.
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                context.resources.openRawResourceFd(kind.raw).use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    private fun readyTrack(context: Context, kind: Kind): AudioTrack? = synchronized(this) {
        val file = customFile(context, kind)
        customTracks[kind]?.takeIf { it.stamp == file.lastModified() }?.track
    }

    private fun ensureDecoded(context: Context, kind: Kind) {
        val file = customFile(context, kind)
        if (!file.exists()) return
        val stamp = file.lastModified()
        synchronized(this) {
            if (customTracks[kind]?.stamp == stamp || !decoding.add(kind)) return
        }
        Thread {
            val track = runCatching { decodeToTrack { it.setDataSource(file.absolutePath) } }.getOrNull()
            synchronized(this) {
                decoding -= kind
                if (track != null) customTracks.put(kind, Decoded(stamp, track))?.track?.release()
            }
        }.apply { isDaemon = true; name = "MetroSounds-decode" }.start()
    }

    private fun playCustom(context: Context, kind: Kind, file: File) {
        val track = readyTrack(context, kind)
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.reloadStaticData()
                track.play()
            }
            return
        }
        ensureDecoded(context.applicationContext, kind)
        // Not decoded yet (first use right after picking a file): play this once directly.
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    /** Decodes the first audio track of [file] to 16-bit PCM, boosts it and wraps it in a static AudioTrack. */
    private fun decodeToTrack(source: (MediaExtractor) -> Unit): AudioTrack {
        val extractor = MediaExtractor()
        try {
            source(extractor)
            val index = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val out = ByteArrayOutputStream()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (out.size() < MAX_DECODED_BYTES) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(chunk)
                        out.write(chunk)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val bytes = out.toByteArray()
            val pcm = ShortArray(bytes.size / 2) { i ->
                ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort()
            }
            require(pcm.isNotEmpty())
            boost(pcm)
            return AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(pcm, 0, pcm.size) }
        } finally {
            extractor.release()
        }
    }

    /** Normalises to full scale, then applies a tanh soft-clip boost (peak stays just under full scale). */
    private fun boost(pcm: ShortArray) {
        val peak = pcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        val ceiling = tanh(CUSTOM_GAIN)
        for (i in pcm.indices) {
            val x = pcm[i].toDouble() / peak
            pcm[i] = (tanh(x * CUSTOM_GAIN) / ceiling * 0.98 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun playBuiltIn(kind: Kind) {
        runCatching {
            val track = tracks.getOrPut(kind) { buildTrack(if (kind == Kind.Lock) lockPcm() else unlockPcm()) }
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(pcm, 0, pcm.size) }

    // ------------------------------------------------------------------ synthesis

    /**
     * WP8's lock / unlock sounds are a *tiny* click each, the two differing only slightly in tone.
     * Both are a single ~30 ms tick: a very short noise transient for the "edge" plus a fast-decaying
     * resonance that gives the pitch. Lock sits lower, unlock a little higher and brighter.
     */
    private fun lockPcm(): ShortArray = tick(seed = 7, resonanceHz = 1_900.0, bodyHz = 520.0, peak = 0.7)

    private fun unlockPcm(): ShortArray = tick(seed = 11, resonanceHz = 2_600.0, bodyHz = 700.0, peak = 0.65)

    private fun tick(seed: Long, resonanceHz: Double, bodyHz: Double, peak: Double): ShortArray {
        val noise = Random(seed)
        return synth(durationMs = 32, peak = peak) { t ->
            val edge = (noise.nextDouble() * 2 - 1) * exp(-t / 0.0006) * 0.35
            val ring = sin(2 * PI * resonanceHz * t) * exp(-t / 0.0035) * 0.8
            val body = sin(2 * PI * bodyHz * t) * exp(-t / 0.006) * 0.3
            edge + ring + body
        }
    }

    /** Renders [sample] to 16-bit PCM, normalised to [peak], with a 2 ms fade-out. */
    private fun synth(durationMs: Int, peak: Double = 0.8, sample: (Double) -> Double): ShortArray {
        val count = SAMPLE_RATE * durationMs / 1000
        val raw = DoubleArray(count) { i -> sample(i.toDouble() / SAMPLE_RATE) }
        val maxAbs = raw.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val fade = SAMPLE_RATE * 2 / 1000
        return ShortArray(count) { i ->
            val envelope = if (i > count - fade) (count - i).toDouble() / fade else 1.0
            (raw[i] / maxAbs * peak * envelope * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
