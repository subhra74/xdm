package xdm.app.utils

import xdm.app.AppContext
import xdm.app.DownloadCompleteNotification
import xdm.core.util.Logger
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlin.concurrent.thread

/**
 * The "Play sound" setting: a WAV when a download finishes or fails. The user's own file is played
 * when set and readable, else the bundled one. Callable from any thread; playback runs on a daemon
 * thread and a missing audio device only gets logged.
 */
object SoundPlayer {
    enum class Sound(val resource: String) {
        COMPLETE("/sounds/complete.wav"),
        FAILED("/sounds/failed.wav"),
    }

    /** Plays [sound] if the setting is on and completion notifications are not turned off. */
    fun playIfEnabled(sound: Sound) {
        val config = AppContext.config
        if (!config.playSound || config.downloadCompleteNotification == DownloadCompleteNotification.NONE) return
        val custom = when (sound) {
            Sound.COMPLETE -> config.completeSoundFile
            Sound.FAILED -> config.failedSoundFile
        }
        play(sound, custom)
    }

    /** Plays [customFile] if it is a playable WAV, else the bundled [sound]. */
    fun play(sound: Sound, customFile: String = "") {
        thread(isDaemon = true, name = "play-sound") {
            val played = customFile.isNotBlank() && runCatching { play(File(customFile).inputStream()) }
                .onFailure { Logger.error("XDM", "Can't play $customFile, using the default sound", it) }
                .isSuccess
            if (!played) {
                runCatching { SoundPlayer::class.java.getResourceAsStream(sound.resource)?.let(::play) }
                    .onFailure { Logger.error("XDM", "Can't play ${sound.resource}", it) }
            }
        }
    }

    /** True if [file] is a WAV that Java Sound can read. */
    fun isPlayableWav(file: File): Boolean = runCatching {
        AudioSystem.getAudioFileFormat(file).type == AudioFileFormat.Type.WAVE
    }.getOrDefault(false)

    private fun play(input: InputStream) {
        val clip = AudioSystem.getClip()
        try {
            // Clip.open reads every frame, so the stream can close before playback starts. The
            // buffering gives getAudioInputStream the mark/reset it needs on a resource stream.
            input.use { AudioSystem.getAudioInputStream(BufferedInputStream(it)).use { audio -> clip.open(audio) } }
        } catch (e: Exception) {
            clip.close()
            throw e
        }
        clip.addLineListener { e -> if (e.type == LineEvent.Type.STOP) clip.close() }
        clip.start()
    }
}
