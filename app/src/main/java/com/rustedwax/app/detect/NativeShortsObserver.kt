package com.rustedwax.app.detect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local bridge between the separately granted service and SessionProbe. */
object NativeShortsObserver {

	data class Status(
		val connected: Boolean = false,
		val completePlayerProof: Boolean = false,
		val activeTitle: String? = null,
		val activeHandle: String? = null,
		val currentSeconds: Long? = null,
		val totalSeconds: Long? = null,
		val lastObservationAtMillis: Long? = null,
		val exactReason: String = "native Shorts accessibility service is not connected",
	)

	sealed interface Event {
		data object Connected : Event
		data class Parsed(val result: NativeShortParser.Result, val observedAtMillis: Long) : Event
		data class Missing(
			val reason: String,
			val observedAtMillis: Long,
			/** Carries [NativeShortParser.Result.Invalid.progressSurfaceLost] through. */
			val progressSurfaceLost: Boolean = false,
			/** Evidence the Short is still playing despite having no progress surface. */
			val inferredPlaying: Boolean = false,
		) : Event
		data class Disconnected(val reason: String) : Event
	}

	private val _status = MutableStateFlow(Status())
	val status: StateFlow<Status> = _status.asStateFlow()
	private val stabilizer = NativeShortStabilizer()

	@Volatile
	var onEvent: ((Event) -> Unit)? = null

	@Volatile
	private var refreshNeeded = false

	fun connected() {
		stabilizer.reset()
		_status.value = _status.value.copy(
			connected = true,
			exactReason = "connected; waiting for a complete foreground Shorts player",
		)
		onEvent?.invoke(Event.Connected)
	}

	fun parsed(result: NativeShortParser.Result, observedAtMillis: Long) {
		when (val decision = stabilizer.observe(result, observedAtMillis)) {
			is NativeShortStabilizer.Decision.Waiting -> {
				_status.value = _status.value.copy(
					connected = true,
					completePlayerProof = false,
					activeTitle = null,
					activeHandle = null,
					currentSeconds = null,
					totalSeconds = null,
					lastObservationAtMillis = observedAtMillis,
					exactReason = decision.reason,
				)
				onEvent?.invoke(Event.Missing(decision.reason, observedAtMillis))
				return
			}
			is NativeShortStabilizer.Decision.Accepted -> Unit
		}
		_status.value = when (result) {
			is NativeShortParser.Result.Organic -> Status(
				connected = true,
				completePlayerProof = true,
				activeTitle = result.title,
				activeHandle = result.ownerHandle,
				currentSeconds = result.currentSeconds,
				totalSeconds = result.totalSeconds,
				lastObservationAtMillis = observedAtMillis,
				exactReason = "complete foreground Short proof",
			)
			is NativeShortParser.Result.Ad -> Status(
				connected = true,
				completePlayerProof = true,
				activeTitle = result.title,
				currentSeconds = result.currentSeconds,
				totalSeconds = result.totalSeconds,
				lastObservationAtMillis = observedAtMillis,
				exactReason = "complete foreground Short player with literal ad label \"${result.signal}\"",
			)
			is NativeShortParser.Result.Invalid -> _status.value.copy(
				connected = true,
				completePlayerProof = false,
				activeTitle = null,
				activeHandle = null,
				currentSeconds = null,
				totalSeconds = null,
				lastObservationAtMillis = observedAtMillis,
				exactReason = result.reason,
			)
		}
		onEvent?.invoke(Event.Parsed(result, observedAtMillis))
	}

	fun missing(
		reason: String,
		observedAtMillis: Long,
		progressSurfaceLost: Boolean = false,
		inferredPlaying: Boolean = false,
	) {
		stabilizer.reset()
		_status.value = _status.value.copy(
			connected = true,
			completePlayerProof = false,
			activeTitle = null,
			activeHandle = null,
			currentSeconds = null,
			totalSeconds = null,
			lastObservationAtMillis = observedAtMillis,
			exactReason = reason,
		)
		onEvent?.invoke(
			Event.Missing(reason, observedAtMillis, progressSurfaceLost, inferredPlaying),
		)
	}

	fun disconnected(reason: String) {
		stabilizer.reset()
		refreshNeeded = false
		_status.value = Status(exactReason = reason)
		onEvent?.invoke(Event.Disconnected(reason))
	}

	fun setRefreshNeeded(value: Boolean) {
		refreshNeeded = value
	}

	fun shouldRefresh(): Boolean = refreshNeeded
}
