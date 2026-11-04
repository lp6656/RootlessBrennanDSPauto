package me.timschneeberger.rootlessjamesdsp.service

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.EngineUtils.toggleEnginePower
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class CarAudioControlService : MediaBrowserServiceCompat(), KoinComponent {

    private val preferences: Preferences.App by inject()
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "BrennanDSP-CarAudioControl").apply {
            setCallback(SessionCallback())
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        updatePlaybackState()
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(null)
            return
        }

        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        val isPowered = isEnginePowered()
        val isStereoWide = preferences.get<Boolean>(R.string.key_stereowide_enable)
        val isLiveProg = preferences.get<Boolean>(R.string.key_liveprog_enable)
        val isBass = preferences.get<Boolean>(R.string.key_bass_enable)

        // Item 1: Master DSP Power Toggle
        items.add(
            createMediaItem(
                id = MEDIA_ID_POWER,
                title = if (isPowered) "⚡ Master DSP: ON" else "⭕ Master DSP: OFF",
                subtitle = if (isPowered) "Tap to turn OFF DSP audio engine" else "Tap to turn ON DSP audio engine"
            )
        )

        // Item 2: Stereo Widener
        items.add(
            createMediaItem(
                id = MEDIA_ID_STEREO,
                title = if (isStereoWide) "🎧 Stereo Widener: ON" else "🎧 Stereo Widener: OFF",
                subtitle = if (isStereoWide) "Soundstage widening active" else "Tap to activate stereo soundstage"
            )
        )

        // Item 3: LiveProg Widener
        items.add(
            createMediaItem(
                id = MEDIA_ID_LIVEPROG,
                title = if (isLiveProg) "🎵 LiveProg Widener: ON" else "🎵 LiveProg Widener: OFF",
                subtitle = if (isLiveProg) "EEL2 phase widening script active" else "Tap to activate LiveProg widener"
            )
        )

        // Item 4: Dynamic Bass
        items.add(
            createMediaItem(
                id = MEDIA_ID_BASS,
                title = if (isBass) "🔊 Dynamic Bass: ON" else "🔊 Dynamic Bass: OFF",
                subtitle = if (isBass) "Sub-bass harmonic boost active" else "Tap to activate dynamic bass"
            )
        )

        // Item 5: Bypass All Modules
        items.add(
            createMediaItem(
                id = MEDIA_ID_BYPASS,
                title = "⏸️ Bypass All Modules",
                subtitle = "Turn off all active DSP modules (flat pass-through)"
            )
        )

        result.sendResult(items)
        updatePlaybackState()
    }

    private fun createMediaItem(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    private fun isEnginePowered(): Boolean {
        return (isRootless() && BaseAudioProcessorService.activeServices > 0) ||
                (!isRootless() && preferences.get<Boolean>(R.string.key_powered_on))
    }

    private fun updatePlaybackState() {
        val isPowered = isEnginePowered()
        val state = if (isPowered) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(actions)
                .build()
        )
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Timber.i("CarAudioControlService: onPlayFromMediaId: $mediaId")
            when (mediaId) {
                MEDIA_ID_POWER -> {
                    val targetPower = !isEnginePowered()
                    toggleEnginePower(targetPower)
                }
                MEDIA_ID_STEREO -> {
                    val current = preferences.get<Boolean>(R.string.key_stereowide_enable)
                    preferences.set(R.string.key_stereowide_enable, !current)
                    sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                }
                MEDIA_ID_LIVEPROG -> {
                    val current = preferences.get<Boolean>(R.string.key_liveprog_enable)
                    preferences.set(R.string.key_liveprog_enable, !current)
                    sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                }
                MEDIA_ID_BASS -> {
                    val current = preferences.get<Boolean>(R.string.key_bass_enable)
                    preferences.set(R.string.key_bass_enable, !current)
                    sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                }
                MEDIA_ID_BYPASS -> {
                    preferences.set(R.string.key_stereowide_enable, false)
                    preferences.set(R.string.key_liveprog_enable, false)
                    preferences.set(R.string.key_bass_enable, false)
                    sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
                }
            }

            notifyChildrenChanged(ROOT_ID)
            updatePlaybackState()
        }

        override fun onPlay() {
            if (!isEnginePowered()) {
                toggleEnginePower(true)
                notifyChildrenChanged(ROOT_ID)
                updatePlaybackState()
            }
        }

        override fun onPause() {
            if (isEnginePowered()) {
                toggleEnginePower(false)
                notifyChildrenChanged(ROOT_ID)
                updatePlaybackState()
            }
        }
    }

    companion object {
        const val ROOT_ID = "brennan_dsp_car_root"
        const val MEDIA_ID_POWER = "action_car_power"
        const val MEDIA_ID_STEREO = "action_car_stereo"
        const val MEDIA_ID_LIVEPROG = "action_car_liveprog"
        const val MEDIA_ID_BASS = "action_car_bass"
        const val MEDIA_ID_BYPASS = "action_car_bypass"
    }
}
