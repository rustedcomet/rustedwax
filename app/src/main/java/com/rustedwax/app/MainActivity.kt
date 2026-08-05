package com.rustedwax.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rustedwax.app.hive.HiveBroadcaster
import com.rustedwax.app.hive.HiveRpc
import com.rustedwax.app.hive.HiveScrobblePayload
import com.rustedwax.app.hive.KeyValidator
import com.rustedwax.app.scrobble.ScrobbleEngine
import com.rustedwax.app.scrobble.ScrobbleRules
import com.rustedwax.app.detect.AccessibilityGrantHealth
import com.rustedwax.app.detect.GrantHealth
import com.rustedwax.app.detect.MonitorSwitch
import com.rustedwax.app.detect.NativeShortsAccessibilityService
import com.rustedwax.app.detect.NativeShortsObserver
import com.rustedwax.app.detect.NativeSourceSwitches
import com.rustedwax.app.detect.ProbeHolder
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.UrlWatcherService
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.ui.MainScreen
import com.rustedwax.app.ui.YouTubeSignInActivity

/**
 * The UI. Detection and scrobbling live in
 * [com.rustedwax.app.detect.RustedWaxListenerService] so they
 * keep running with this activity closed — this class only observes and
 * configures.
 */
class MainActivity : ComponentActivity() {

	private lateinit var vault: KeyVault
	private lateinit var settings: Settings
	private val validator = KeyValidator()
	private val broadcaster = HiveBroadcaster()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		EventLog.init(this)
		MonitorSwitch.init(this)
		NativeSourceSwitches.init(this)
		ScrobbleEngine.init(this)
		vault = KeyVault(this)
		settings = Settings(this)

		setContent {
			MaterialTheme {
				val probe by ProbeHolder.probe.collectAsStateWithLifecycle()
				val monitoring by MonitorSwitch.enabled.collectAsStateWithLifecycle()
				val nativeSources by NativeSourceSwitches.config.collectAsStateWithLifecycle()
				val nativeShortsStatus by NativeShortsObserver.status.collectAsStateWithLifecycle()
				val logLines by EventLog.lines.collectAsStateWithLifecycle()
				val recent by ScrobbleEngine.recent.collectAsStateWithLifecycle()
				val skipped by ScrobbleEngine.skipped.collectAsStateWithLifecycle()
				val quietBar by ScrobbleEngine.tracksWithoutVideoId.collectAsStateWithLifecycle()
				val queued by ScrobbleEngine.queueSize.collectAsStateWithLifecycle()

				var sessions by remember { mutableStateOf(emptyList<com.rustedwax.app.detect.SessionSnapshot>()) }
				var hasAccess by remember { mutableStateOf(SessionProbe.hasNotificationAccess(this)) }
				var urlWatcher by remember { mutableStateOf(UrlWatcherService.isEnabled(this)) }
				var nativeShortsGranted by remember {
					mutableStateOf(NativeShortsAccessibilityService.isEnabled(this))
				}
				// "Off" and "was on and stopped on its own" are the same boolean
				// but very different messages: Android drops a crashed
				// accessibility service from the enabled list, and on 2026-08-05
				// that silently cost most of a day's browser evidence.
				var urlWatcherDropped by remember { mutableStateOf(false) }
				var nativeShortsDropped by remember { mutableStateOf(false) }
				// When each grant last stopped being live. §2.3: the master flag
				// reads 0 for a few seconds after an install and then returns on
				// its own, so a drop is only a drop once it has outlasted that.
				var urlWatcherNotLiveSince by remember { mutableStateOf(0L) }
				var nativeShortsNotLiveSince by remember { mutableStateOf(0L) }
				var enrichment by remember { mutableStateOf(settings.enrichment) }
				var shortClips by remember { mutableStateOf(settings.shortClips) }
				// Read once per composition rather than held in a flow: mutes change
				// only by the button below, so recomposing on that is enough.
				var mutedIds by remember { mutableStateOf(ScrobbleEngine.mutedVideos().keys) }
				var account by remember { mutableStateOf(vault.account) }
				var autoScrobble by remember { mutableStateOf(settings.autoScrobble) }
				// Re-read on every poll: the session is written by the sign-in
				// activity, and the refusal by the resolver on a background
				// thread, so neither can be cached in composition.
				var youTubeAccount by remember { mutableStateOf(ScrobbleEngine.youTubeSession()) }
				var watchHistory by remember { mutableStateOf(ScrobbleEngine.watchHistoryEnabled()) }
				var watchHistoryRefusal by remember {
					mutableStateOf(ScrobbleEngine.watchHistoryRefusal())
				}
					var busy by remember { mutableStateOf(false) }
					var status by remember { mutableStateOf<String?>(null) }
					var statusIsError by remember { mutableStateOf(false) }
					var probeError by remember { mutableStateOf<String?>(null) }

					// Observe errors as a flow. Reading StateFlow.value directly in
					// composition does not subscribe and can leave stale UI.
					LaunchedEffect(probe) {
						probeError = null
						probe?.error?.collect { probeError = it }
					}

				// The probe belongs to the service; poll it for live position
				// and re-check the grant, which can be revoked from Settings.
				LaunchedEffect(probe) {
					while (true) {
						hasAccess = SessionProbe.hasNotificationAccess(this@MainActivity)
						// Both grants are revocable from system settings while
						// we're in the background, so neither is cached.
						urlWatcher = UrlWatcherService.isEnabled(this@MainActivity)
						nativeShortsGranted =
							NativeShortsAccessibilityService.isEnabled(this@MainActivity)
						val nowMillis = System.currentTimeMillis()
						val browserGrant = noteGrant(
							live = urlWatcher,
							everGranted = settings.browserEvidenceEverGranted,
							remember = { settings.browserEvidenceEverGranted = it },
							alreadyReported = urlWatcherDropped,
							notLiveSince = urlWatcherNotLiveSince,
							nowMillis = nowMillis,
							label = "Browser evidence access",
						)
						urlWatcherDropped = browserGrant.dropped
						urlWatcherNotLiveSince = browserGrant.notLiveSince
						val shortsGrant = noteGrant(
							live = nativeShortsGranted,
							everGranted = settings.nativeShortsEverGranted,
							remember = { settings.nativeShortsEverGranted = it },
							alreadyReported = nativeShortsDropped,
							notLiveSince = nativeShortsNotLiveSince,
							nowMillis = nowMillis,
							label = "Foreground Shorts evidence",
						)
						nativeShortsDropped = shortsGrant.dropped
						nativeShortsNotLiveSince = shortsGrant.notLiveSince
						youTubeAccount = ScrobbleEngine.youTubeSession()
						watchHistory = ScrobbleEngine.watchHistoryEnabled()
						watchHistoryRefusal = ScrobbleEngine.watchHistoryRefusal()
						probe?.tick()
						sessions = probe?.sessions?.value ?: emptyList()
						delay(1000)
					}
				}

				fun report(message: String, isError: Boolean) {
					status = message
					statusIsError = isError
					EventLog.append(if (isError) "error" else "hive", message)
				}

				MainScreen(
					sessions = sessions,
					logLines = logLines,
					hasAccess = hasAccess,
					serviceRunning = probe != null,
						error = probeError,
					account = account,
					accountBusy = busy,
					accountStatus = status,
					accountStatusIsError = statusIsError,
					monitoring = monitoring,
					autoScrobble = autoScrobble,
					nativeYouTube = nativeSources.youtubeEnabled,
					nativeYouTubeMusic = nativeSources.youtubeMusicEnabled,
					nativeShortsGranted = nativeShortsGranted,
					nativeShortsDropped = nativeShortsDropped,
					nativeShortsStatus = nativeShortsStatus,
					thresholdPercent = settings.thresholdPercent,
					urlWatcherEnabled = urlWatcher,
					urlWatcherDropped = urlWatcherDropped,
					enrichment = enrichment,
					shortClips = shortClips,
					recent = recent,
					skipped = skipped,
					mutedIds = mutedIds,
					tracksWithoutVideoId = quietBar,
					queuedCount = queued,
					youTubeAccount = youTubeAccount,
					watchHistory = watchHistory,
					watchHistoryRefusal = watchHistoryRefusal,
					onToggleWatchHistory = { enabled ->
						ScrobbleEngine.setWatchHistoryEnabled(enabled)
						watchHistory = enabled
						watchHistoryRefusal = ScrobbleEngine.watchHistoryRefusal()
					},
					onConnectYouTube = {
						startActivity(Intent(this@MainActivity, YouTubeSignInActivity::class.java))
					},
					onDisconnectYouTube = {
						ScrobbleEngine.disconnectYouTubeSession()
						youTubeAccount = null
						watchHistory = false
						watchHistoryRefusal = null
						report(
							"YouTube account disconnected and its session wiped from this device.",
							isError = false,
						)
					},
					onGrantAccess = ::openNotificationAccessSettings,
					onExportLog = ::exportLog,
					onClearLog = EventLog::clear,
					onToggleMonitoring = { enabled ->
						MonitorSwitch.set(this@MainActivity, enabled)
						report(
							if (enabled) {
								"Monitoring started."
							} else {
								"Monitoring stopped. Nothing is being read; " +
									"anything already queued still sends."
							},
							isError = false,
						)
					},
					onToggleNativeYouTube = { enabled ->
						NativeSourceSwitches.setYouTube(this@MainActivity, enabled)
					},
					onToggleNativeYouTubeMusic = { enabled ->
						NativeSourceSwitches.setYouTubeMusic(this@MainActivity, enabled)
					},
					onOpenAccessibility = ::openAccessibilitySettings,
					onToggleEnrichment = { enabled ->
						settings.enrichment = enabled
						enrichment = enabled
						EventLog.append(
							"enrich",
							"video lookup ${if (enabled) "on" else "off"}",
						)
					},
					onToggleShortClips = { enabled ->
						settings.shortClips = enabled
						shortClips = enabled
						EventLog.append(
							"engine",
							"short-clip scrobbling ${if (enabled) "on" else "off"} — " +
								"verified shorts count from " +
								"${if (enabled) {
									ScrobbleRules.SHORT_MIN_DURATION_SECONDS
								} else {
									ScrobbleRules.MIN_DURATION_SECONDS
								}}s",
						)
					},
					onToggleAutoScrobble = { enabled ->
						if (enabled && account == null) {
							report("Add a Hive key first — nothing can be signed.", true)
						} else {
							ScrobbleEngine.setAutoScrobble(enabled)
							autoScrobble = enabled
						}
					},
					onMute = { record ->
						val id = record.videoId ?: return@MainScreen
						val label = record.artist?.let { "$it — ${record.title}" } ?: record.title
						ScrobbleEngine.mute(id, label)
						mutedIds = ScrobbleEngine.mutedVideos().keys
						report(
							"\"$label\" won't scrobble again. The entry already " +
								"on-chain can't be removed — nothing can remove it.",
							isError = false,
						)
					},
					onRetryQueue = {
						ScrobbleEngine.flushQueue()
						report("Retrying queued scrobbles…", false)
					},
					onValidateAndSave = { username, wif ->
						busy = true
						status = "Checking @$username's posting authority on-chain…"
						statusIsError = false
						lifecycleScope.launch {
							when (val result = withContext(Dispatchers.IO) {
								validator.validate(username, wif)
							}) {
								is KeyValidator.Result.Valid -> {
									vault.save(result.username, wif, result.publicKey)
									account = vault.account
									report(
										"Key verified against @${result.username}'s posting " +
											"authority and saved.",
										isError = false,
									)
								}

								is KeyValidator.Result.Invalid ->
									report(result.reason, isError = true)
							}
							busy = false
						}
					},
					onForgetKey = {
						vault.forget()
						account = null
						ScrobbleEngine.setAutoScrobble(false)
						autoScrobble = false
						report("Key wiped from this device. Auto-scrobble off.", isError = false)
					},
					onBroadcastTest = {
						busy = true
						broadcast(ScrobbleBuilder.testPayload()) { msg, err ->
							busy = false
							report(msg, err)
						}
					},
					onBroadcastSession = { session ->
						// Same cached facts the Now card showed — the button must
						// broadcast exactly the payload the user just looked at.
						val facts = session.confirmed
							?.let { ScrobbleEngine.cachedFacts(it.videoId) }
							val mb = ScrobbleEngine.cachedMusicMatch(session, facts)
							val durationMs = ScrobbleBuilder.effectiveDurationMs(session, facts)
							val payload = ScrobbleBuilder.from(
								session,
								facts,
								mb,
								durationMs = durationMs,
							)
							val decision = ScrobbleRules.decide(
								playedMs = session.playedMs,
								durationMs = durationMs,
								threshold = settings.scrobbleThreshold,
								isShort = session.hasShortSourceProof,
								videoResolved = facts?.resolvedOnWatchPage == true,
								videoUnlisted = facts?.isUnlisted,
								shortClipsEnabled = settings.shortClips,
								explicitAdSignal = session.explicitAdSignal,
								browserEvidenceEnabled = session.browserEvidenceEnabled,
								resolvedWithoutExactUrl = session.confirmed == null,
								accessibilityCovered = session.accessibilityCoverage != null,
							)
							val startedAt = session.trackStartedAtEpochSec
							when {
								!NativeSourceSwitches.isSnapshotCurrent(
									session.packageName,
									session.sourceEpoch,
								) -> report(
									"That native source was disabled or reset. Nothing sent.",
									isError = true,
								)

								ScrobbleRules.browserEvidenceUnavailableReason(
									browserEvidenceEnabled = session.browserEvidenceEnabled,
									resolvedWithoutExactUrl = session.confirmed == null,
									accessibilityCovered = session.accessibilityCoverage != null,
								) != null ->
									report(
										"Not eligible under the automatic rules: " +
											decision.skippedBecause,
										isError = true,
									)

								session.confirmed == null ->
									report(
										"Video ID not verified — nothing sent because every " +
											"scrobble requires a hyperlink.",
										isError = true,
									)

								payload == null ->
									report("Nothing broadcastable in that session yet.", isError = true)

							// The mute has to bind here too, or this button is a
							// way around it — the same reason the manual path was
							// made to share the dedup ledger in v0.5.4.
							session.confirmed?.videoId?.let { ScrobbleEngine.isMuted(it) } == true ->
								report(
									"That video is muted — you asked never to scrobble " +
										"it again. Nothing sent.",
										isError = true,
									)

								!decision.shouldScrobble ->
									report(
										"Not eligible under the automatic rules: " +
											(decision.skippedBecause ?: "no qualifying decision"),
										isError = true,
									)

								// Shares the automatic path's ledger, so this button is
								// idempotent and the later automatic finalize of the same
								// track is blocked instead of duplicating it.
							!ScrobbleEngine.claimManual(payload, startedAt) ->
								report(
									"Already scrobbled — \"${payload.title}\" is in the " +
										"ledger for this listen. Nothing sent.",
									isError = true,
								)

								else -> {
									val manualPayload = payload.copy(
										percentPlayed = ScrobbleRules.capForKind(
											decision.percentages,
											payload.kind,
											isShort = session.hasShortSourceProof,
											loopDetected = session.loopDetected,
										).first(),
									)
									busy = true
									broadcast(
										payload = manualPayload,
										sourcePackage = session.packageName,
										sourceEpoch = session.sourceEpoch,
									) { msg, err ->
										busy = false
									// No queue on this path, so a failed send must give
									// the claim back or the listen is stuck.
										if (err) {
											ScrobbleEngine.releaseManualClaim(manualPayload, startedAt)
										}
									report(msg, err)
								}
							}
						}
					},
				)
			}
		}
	}

	/** Manual broadcast, still available alongside automatic scrobbling. */
	private fun broadcast(
		payload: HiveScrobblePayload,
		sourcePackage: String? = null,
		sourceEpoch: Long? = null,
		onDone: (message: String, isError: Boolean) -> Unit,
	) {
		if (sourcePackage != null &&
			!NativeSourceSwitches.isSnapshotCurrent(sourcePackage, sourceEpoch)
		) {
			onDone("That native source was disabled or reset. Nothing sent.", true)
			return
		}
		val saved = vault.account
		val key = vault.loadKey()
		if (saved == null || key == null) {
			onDone("No key saved — add one on the Account tab first.", true)
			return
		}

		EventLog.append("hive", "broadcasting as @${saved.username}: ${payload.toJson()}")

		lifecycleScope.launch {
			if (sourcePackage != null &&
				!NativeSourceSwitches.isSnapshotCurrent(sourcePackage, sourceEpoch)
			) {
				onDone("That native source was disabled or reset. Nothing sent.", true)
				return@launch
			}
			val result = withContext(Dispatchers.IO) {
				runCatching { broadcaster.broadcastScrobble(saved.username, key, payload) }
					.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "unknown") }
			}
			when (result) {
				is HiveRpc.BroadcastResult.Success -> {
					val message = when (result.evidence) {
						HiveRpc.BroadcastResult.Evidence.BLOCK ->
							"Confirmed in a block — tx ${result.txId}"
						HiveRpc.BroadcastResult.Evidence.MEMPOOL ->
							"Accepted by ${result.node.substringAfter("//")} and seen " +
								"relaying in an independent node's mempool — tx ${result.txId}"
					}
					onDone(message, false)
				}

				is HiveRpc.BroadcastResult.AcceptedUnconfirmed ->
					// Not an error callback: the manual claim must remain held.
					// Retrying an accepted-but-ambiguous transaction can duplicate it.
					onDone(
						"Accepted by ${result.node.substringAfter("//")}, but confirmation " +
							"was unavailable — do not retry" +
							(result.txId?.let { " — tx $it" } ?: ""),
						false,
					)

				is HiveRpc.BroadcastResult.Rejected ->
					onDone("Chain rejected it: ${result.message}", true)

				// No queue behind this path, so the honest answer is "not yet"
				// plus what to do about it.
				is HiveRpc.BroadcastResult.Deferred ->
					onDone(
						"Not on-chain yet — ${result.message}. Nothing was signed " +
							"away; try again in a moment.",
						true,
					)

				is HiveRpc.BroadcastResult.NetworkFailure ->
					onDone("Couldn't reach a node: ${result.message}", true)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		// Coming back to the app is a good moment to drain anything queued.
		ScrobbleEngine.flushQueue()
	}

	private fun openNotificationAccessSettings() {
		startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
	}

	/**
	 * There is no intent that jumps straight to one service's toggle, so this
	 * lands on the Accessibility list and the user picks RustedWax from it.
	 *
	 * On Android 13+ a sideloaded APK's accessibility toggle is greyed out
	 * until "Allow restricted settings" is granted from App info — the toggle
	 * looks broken rather than blocked, so the app says so up front.
	 */
	private fun openAccessibilitySettings() {
		EventLog.append(
			"url",
			"opening Accessibility settings — on Android 13+ a sideloaded build " +
				"needs 'Allow restricted settings' from App info first",
		)
		startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
	}

	private fun exportLog() {
		val file = EventLog.logFile() ?: return
		if (!file.exists()) return
		val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
		val send = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_STREAM, uri)
			putExtra(Intent.EXTRA_SUBJECT, "RustedWax log")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		startActivity(Intent.createChooser(send, "Export log"))
	}
}

/**
 * Classifies an accessibility grant as live, never granted, or **dropped**.
 *
 * Android removes a crashed accessibility service from
 * `enabled_accessibility_services`, which is indistinguishable from the user
 * switching it off. On 2026-08-05 the browser watcher was dropped exactly that
 * way, stayed off for most of a day, and nothing anywhere said so — roughly
 * half that day's watch history never reached the app. Remembering that a
 * grant was once live turns one boolean into two very different messages.
 *
 * Logged once per transition rather than once per poll, which runs every
 * second: a run of identical lines is what buried the last two diagnoses.
 *
 * @return whether the grant was once live and is not live now.
 */
private data class GrantReport(val dropped: Boolean, val notLiveSince: Long)

private fun noteGrant(
	live: Boolean,
	everGranted: Boolean,
	remember: (Boolean) -> Unit,
	alreadyReported: Boolean,
	notLiveSince: Long,
	nowMillis: Long,
	label: String,
): GrantReport {
	if (live) {
		if (!everGranted) remember(true)
		return GrantReport(dropped = false, notLiveSince = 0L)
	}
	val since = if (notLiveSince == 0L) nowMillis else notLiveSince
	val health = AccessibilityGrantHealth.classify(
		live = false,
		everGranted = everGranted,
		notLiveForMillis = nowMillis - since,
	)
	if (health == GrantHealth.DROPPED && !alreadyReported) {
		EventLog.append(
			"health",
			"$label was granted and is no longer enabled. Android disables an " +
				"accessibility service when it crashes, so this can happen without " +
				"anyone changing a setting. Nothing is being read through it.",
		)
	}
	return GrantReport(dropped = health == GrantHealth.DROPPED, notLiveSince = since)
}
