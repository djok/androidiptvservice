package com.bytecoders.androidtv.iptvservice.rich

import android.content.Context
import android.media.tv.TvContentRating
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import com.bytecoders.androidtv.iptvservice.player.TvExoPlayer
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
import com.google.android.exoplayer2.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistParserFactory
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelection
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.util.EventLogger
import com.google.android.exoplayer2.util.Util
import com.google.android.media.tv.companionlibrary.BaseTvInputService
import com.google.android.media.tv.companionlibrary.TvPlayer
import com.google.android.media.tv.companionlibrary.model.InternalProviderData
import com.google.android.media.tv.companionlibrary.model.Program
import com.google.android.media.tv.companionlibrary.model.RecordedProgram

/**
 * Taken from DR channels app: https://github.com/clhols/drchannels
 */

class RichTvInputService : BaseTvInputService() {
    override fun onCreateSession(inputId: String): TvInputService.Session {
        val session = DrTvInputSessionImpl(this, inputId).apply {
            setOverlayViewEnabled(true)
        }

        return super.sessionCreated(session)
    }
}

class DrTvInputSessionImpl(
        private val context: Context,
        inputId: String
) : BaseTvInputService.Session(context, inputId), Player.EventListener {

    private val tag = DrTvInputSessionImpl::class.java.simpleName
    private val unknownType = -1

    private val defaultBandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
    private val trackSelector = DefaultTrackSelector()
    private val eventLogger = EventLogger(trackSelector)
    private val dataSourceFactory: DataSource.Factory = buildDataSourceFactory()

    private var player: TvExoPlayer? = null

    private fun initPlayer(providerData: InternalProviderData) {
        Log.d(tag, "Loading providerData $providerData")

        val renderersFactory = DefaultRenderersFactory(context)

        releasePlayer()

        // Enable tunneling if supported by the current media and device configuration.
        trackSelector.apply {
            setParameters(
                    buildUponParameters().setTunnelingAudioSessionId(
                            C.generateAudioSessionIdV21(context)))
        }

        val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        5000,
                        DEFAULT_MAX_BUFFER_MS,
                        1500,
                        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .createDefaultLoadControl()

        player = TvExoPlayer(context, renderersFactory, trackSelector, loadControl, defaultBandwidthMeter).apply {
            addListener(this@DrTvInputSessionImpl)
            addAnalyticsListener(eventLogger)
            prepare(buildMediaSource(providerData.videoUrl.toUri()), true, false)
        }
    }

    override fun onPlayProgram(program: Program?, startPosMs: Long): Boolean {
        if (program == null) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            return false
        }

        initPlayer(program.internalProviderData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
        }

        player?.playWhenReady = true

        return true
    }

    override fun onPlayRecordedProgram(recordedProgram: RecordedProgram?): Boolean {
        Log.i(tag, "onPlayRecordedProgram $recordedProgram")
        if (recordedProgram == null) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            return false
        }

        initPlayer(recordedProgram.internalProviderData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
        }

        onSetStreamVolume(1.0f)

        player?.playWhenReady = true

        return true
    }

    override fun onTune(channelUri: Uri?, params: Bundle?): Boolean {
        Log.d(tag, "Tune to " + channelUri.toString())

        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        releasePlayer()

        return super.onTune(channelUri, params)
    }

    override fun onRelease() {
        Log.d(tag, "onRelease")
        super.onRelease()
        releasePlayer()
    }

    override fun onBlockContent(rating: TvContentRating) {
        Log.d(tag, "onBlockContent $rating")
        super.onBlockContent(rating)
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.apply {
            removeListener(this@DrTvInputSessionImpl)
            setSurface(null)
            stop()
            release()
        }
        player = null
    }

    override fun getTvPlayer(): TvPlayer? = player

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        if (player == null) {
            return
        }

        Log.d(tag, "onPlayerStateChanged $playWhenReady with state $playbackState")

        if (playWhenReady && playbackState == Player.STATE_READY) {
            handlePlayerReady()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Math.abs(player!!.playbackParameters.speed - 1) < 0.1 &&
                playWhenReady && playbackState == Player.STATE_BUFFERING) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
        }
    }

    private fun handlePlayerReady() {
        val tracks = ArrayList<TvTrackInfo>()

        val trackSelections = player!!.currentTrackSelections.all.filter { it != null }
        trackSelections.forEach { trackSelection ->

            val format = trackSelection.selectedFormat
            val formatId = format.id
            val trackType = getTrackType(trackSelection)
            if (trackType != unknownType && formatId != null) {
                val builder = TvTrackInfo.Builder(trackType, formatId)

                when (trackType) {
                    TvTrackInfo.TYPE_VIDEO -> {
                        if (format.width != Format.NO_VALUE) {
                            builder.setVideoWidth(format.width)
                        }
                        if (format.height != Format.NO_VALUE) {
                            builder.setVideoHeight(format.height)
                        }
                    }
                    TvTrackInfo.TYPE_AUDIO -> {
                        builder.setAudioChannelCount(format.channelCount)
                        builder.setAudioSampleRate(format.sampleRate)
                        builder.setLanguage(format.language)
                    }
                    TvTrackInfo.TYPE_SUBTITLE -> {
                        builder.setLanguage(format.language)
                    }
                }

                tracks.add(builder.build())
            }
        }
        notifyTracksChanged(tracks)

        val selectedFormats = trackSelections.map { it.selectedFormat }

        notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, getAudioId(selectedFormats))
        notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, getVideoId(selectedFormats))
        //notifyTrackSelected(TvTrackInfo.TYPE_SUBTITLE, textId)
        notifyVideoAvailable()
    }

    private fun getTrackType(trackSelection: TrackSelection): Int {
        val mimeType = trackSelection.selectedFormat.sampleMimeType!!
        if (mimeType.contains("audio/")) {
            return TvTrackInfo.TYPE_AUDIO
        }
        if (mimeType.contains("video/")) {
            return TvTrackInfo.TYPE_VIDEO
        }
        return unknownType
    }

    override fun onSeekProcessed() {
        Log.d(tag, "onSeekProcessed")
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        Log.d(tag, "onShuffleModeEnabledChanged $shuffleModeEnabled")
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {
        Log.d(tag, "onSetCaptionEnabled $enabled")
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        Log.d(tag, "onPlaybackParametersChanged $playbackParameters")
    }

    override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
        Log.d(tag, "onTracksChanged $trackSelections")
    }

    override fun onPlayerError(error: ExoPlaybackException) {
        Log.e(tag, error.message ?: "Unknown error", error)

        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
    }

    override fun onLoadingChanged(isLoading: Boolean) {
        Log.d(tag, "onLoadingChanged $isLoading")
    }

    override fun onPositionDiscontinuity(reason: Int) {
        Log.d(tag, "onPositionDiscontinuity $reason")
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        Log.i(tag, "onRepeatModeChanged $repeatMode")
    }

    override fun onTimelineChanged(timeline: Timeline, manifest: Any?, reason: Int) {
        Log.d(tag, "onTimelineChanged $timeline $manifest $reason")
    }

    private fun getVideoId(selectedFormats: List<Format>) =
            selectedFormats.firstOrNull { it.sampleMimeType?.contains("video/") ?: false }?.id
                    ?: "0"

    private fun getAudioId(selectedFormats: List<Format>) =
            selectedFormats.firstOrNull { it.sampleMimeType?.contains("audio/") ?: false }?.id
                    ?: "0"


    private fun buildMediaSource(uri: Uri, overrideExtension: String = ""): MediaSource {
        when (@C.ContentType val type = Util.inferContentType(uri, overrideExtension)) {
            /*
            C.TYPE_DASH -> return DashMediaSource.Factory(dataSourceFactory)
                    .setManifestParser(
                            FilteringManifestParser<DashManifest>(DashManifestParser(),
                                    getOfflineStreamKeys(uri)))
                    .createMediaSource(uri)
            C.TYPE_SS -> return SsMediaSource.Factory(dataSourceFactory)
                    .setManifestParser(
                            FilteringManifestParser<SsManifest>(SsManifestParser(),
                                    getOfflineStreamKeys(uri)))
                    .createMediaSource(uri)
            */
            C.TYPE_HLS -> return HlsMediaSource.Factory(dataSourceFactory)
                    .setPlaylistParserFactory(
                            DefaultHlsPlaylistParserFactory())
                    .createMediaSource(uri)
            C.TYPE_OTHER -> return ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri)
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }
    }

    private fun buildDataSourceFactory(): DataSource.Factory {
        return DefaultDataSourceFactory(context, buildHttpDataSourceFactory())
    }

    private fun buildHttpDataSourceFactory(): HttpDataSource.Factory {
        return DefaultHttpDataSourceFactory("${context.packageName} / (Linux;Android ${Build.VERSION.RELEASE}) ")
    }
}