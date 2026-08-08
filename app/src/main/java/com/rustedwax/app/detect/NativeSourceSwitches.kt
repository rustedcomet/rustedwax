package com.rustedwax.app.detect

import android.content.Context
import com.rustedwax.app.storage.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime and persisted opt-ins for the two native YouTube packages.
 *
 * Epochs make disabling, Stop and listener reconnect hard boundaries even for
 * finalization work already running on the engine's IO dispatcher. Browser
 * packages do not use these epochs and retain their v0.8.15 behavior.
 */
object NativeSourceSwitches {

	data class Config(
		val youtubeEnabled: Boolean = false,
		val youtubeMusicEnabled: Boolean = false,
		val youtubeEpoch: Long = 1,
		val youtubeMusicEpoch: Long = 1,
	)

	private var settings: Settings? = null
	private val _config = MutableStateFlow(Config())
	val config: StateFlow<Config> = _config.asStateFlow()

	@Synchronized
	fun init(context: Context) {
		if (settings != null) return
		settings = Settings(context.applicationContext).also { stored ->
			_config.value = _config.value.copy(
				youtubeEnabled = stored.nativeYouTube,
				youtubeMusicEnabled = stored.nativeYouTubeMusic,
			)
		}
	}

	@Synchronized
	fun setYouTube(context: Context, enabled: Boolean) {
		init(context)
		val current = _config.value
		if (current.youtubeEnabled == enabled) return
		settings?.nativeYouTube = enabled
		_config.value = toggled(current, YouTubeProbe.YOUTUBE_PACKAGE, enabled)
		EventLog.append(
			"native",
			"${YouTubeProbe.YOUTUBE_PACKAGE} ${if (enabled) "enabled" else "disabled and cleared"}",
		)
	}

	@Synchronized
	fun setYouTubeMusic(context: Context, enabled: Boolean) {
		init(context)
		val current = _config.value
		if (current.youtubeMusicEnabled == enabled) return
		settings?.nativeYouTubeMusic = enabled
		_config.value = toggled(current, YouTubeProbe.YOUTUBE_MUSIC_PACKAGE, enabled)
		EventLog.append(
			"native",
			"${YouTubeProbe.YOUTUBE_MUSIC_PACKAGE} " +
				"${if (enabled) "enabled" else "disabled and cleared"}",
		)
	}

	/** Invalidate in-flight native snapshots without changing either opt-in. */
	@Synchronized
	fun invalidateAll(reason: String) {
		val current = _config.value
		_config.value = invalidated(current)
		EventLog.append("native", "native source state cleared: $reason")
	}

	/** Invalidate one native package without disturbing the other opt-in/source. */
	@Synchronized
	fun invalidatePackage(packageName: String, reason: String) {
		val current = _config.value
		val next = when (packageName) {
			YouTubeProbe.YOUTUBE_PACKAGE -> current.copy(youtubeEpoch = current.youtubeEpoch + 1)
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
				current.copy(youtubeMusicEpoch = current.youtubeMusicEpoch + 1)
			else -> current
		}
		if (next == current) return
		_config.value = next
		EventLog.append("native", "$packageName source state cleared: $reason")
	}

	fun acceptsPackage(packageName: String): Boolean {
		return accepts(_config.value, packageName)
	}

	fun epochFor(packageName: String): Long? = when (packageName) {
		YouTubeProbe.YOUTUBE_PACKAGE -> _config.value.youtubeEpoch
		YouTubeProbe.YOUTUBE_MUSIC_PACKAGE -> _config.value.youtubeMusicEpoch
		else -> null
	}

	fun isSnapshotCurrent(packageName: String, epoch: Long?): Boolean =
		isSnapshotCurrent(_config.value, packageName, epoch)

	internal fun toggled(current: Config, packageName: String, enabled: Boolean): Config =
		when (packageName) {
			YouTubeProbe.YOUTUBE_PACKAGE -> if (current.youtubeEnabled == enabled) current else {
				current.copy(
					youtubeEnabled = enabled,
					youtubeEpoch = current.youtubeEpoch + 1,
				)
			}
			YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
				if (current.youtubeMusicEnabled == enabled) current else {
					current.copy(
						youtubeMusicEnabled = enabled,
						youtubeMusicEpoch = current.youtubeMusicEpoch + 1,
					)
				}
			else -> current
		}

	internal fun invalidated(current: Config): Config = current.copy(
		youtubeEpoch = current.youtubeEpoch + 1,
		youtubeMusicEpoch = current.youtubeMusicEpoch + 1,
	)

	internal fun accepts(current: Config, packageName: String): Boolean =
		YouTubeProbe.acceptsPackage(
			packageName,
			current.youtubeEnabled,
			current.youtubeMusicEnabled,
		)

	internal fun isSnapshotCurrent(
		current: Config,
		packageName: String,
		epoch: Long?,
	): Boolean = when (packageName) {
		YouTubeProbe.YOUTUBE_PACKAGE -> current.youtubeEnabled && epoch == current.youtubeEpoch
		YouTubeProbe.YOUTUBE_MUSIC_PACKAGE ->
			current.youtubeMusicEnabled && epoch == current.youtubeMusicEpoch
		else -> true
	}
}
