package com.rustedwax.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustedwax.app.scrobble.ScrobbleEngine
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionSnapshot
import com.rustedwax.app.detect.YouTubeProbe
import com.rustedwax.app.enrich.VideoFacts
import com.rustedwax.app.storage.KeyVault
import kotlin.math.roundToInt

/**
 * The whole UI: what's playing now, the Hive account, scrobble history, and the
 * raw event log explaining every decision.
 *
 * Utilitarian by design — this is a tool for one person, and the log being
 * readable matters more than the chrome around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
	sessions: List<SessionSnapshot>,
	logLines: List<String>,
	hasAccess: Boolean,
	serviceRunning: Boolean,
	error: String?,
	account: KeyVault.Account?,
	accountBusy: Boolean,
	accountStatus: String?,
	accountStatusIsError: Boolean,
	monitoring: Boolean,
	autoScrobble: Boolean,
	thresholdPercent: Int,
	urlWatcherEnabled: Boolean,
	enrichment: Boolean,
	shortClips: Boolean,
	recent: List<ScrobbleEngine.ScrobbleRecord>,
	skipped: List<ScrobbleEngine.SkipRecord>,
	mutedIds: Set<String>,
	tracksWithoutVideoId: Int,
	queuedCount: Int,
	onGrantAccess: () -> Unit,
	onExportLog: () -> Unit,
	onClearLog: () -> Unit,
	onToggleMonitoring: (Boolean) -> Unit,
	onOpenAccessibility: () -> Unit,
	onToggleEnrichment: (Boolean) -> Unit,
	onToggleShortClips: (Boolean) -> Unit,
	onToggleAutoScrobble: (Boolean) -> Unit,
	onMute: (ScrobbleEngine.ScrobbleRecord) -> Unit,
	onRetryQueue: () -> Unit,
	onValidateAndSave: (String, String) -> Unit,
	onForgetKey: () -> Unit,
	onBroadcastTest: () -> Unit,
	onBroadcastSession: (SessionSnapshot) -> Unit,
) {
	var tab by remember { mutableIntStateOf(0) }

	Scaffold(
		topBar = {
			TopAppBar(title = { Text("RustedWax") })
		},
	) { padding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
				.padding(horizontal = 12.dp),
		) {
			if (!hasAccess) {
				AccessBanner(onGrantAccess)
			} else if (monitoring && !serviceRunning) {
				// Grant held, monitoring wanted, but the system hasn't (re)bound
				// the listener. Only an error while monitoring is *on* — a user
				// Stop produces the same null probe and is not a fault.
				Text(
					"Waiting for Android to start the listener service — toggle " +
						"Notification Access off and on if this persists.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(top = 8.dp),
				)
			}

			ScrobbleControls(
				monitoring = monitoring,
				autoScrobble = autoScrobble,
				thresholdPercent = thresholdPercent,
				hasKey = account != null,
				queuedCount = queuedCount,
				urlWatcherEnabled = urlWatcherEnabled,
				enrichment = enrichment,
				shortClips = shortClips,
				onToggleMonitoring = onToggleMonitoring,
				onOpenAccessibility = onOpenAccessibility,
				onToggleEnrichment = onToggleEnrichment,
				onToggleShortClips = onToggleShortClips,
				onToggle = onToggleAutoScrobble,
				onRetryQueue = onRetryQueue,
			)
			error?.let {
				Text(
					it,
					color = MaterialTheme.colorScheme.error,
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.padding(vertical = 4.dp),
				)
			}

			// The address bar has stopped naming videos while tracks keep ending.
			// Silent until now: a 13-minute hole in the 2026-07-29 session cost
			// five links and four entries outright, and nothing in the UI said so.
			if (urlWatcherEnabled && tracksWithoutVideoId >= ScrobbleEngine.QUIET_BAR_THRESHOLD) {
				Card(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 8.dp),
					colors = CardDefaults.cardColors(
						containerColor = MaterialTheme.colorScheme.errorContainer,
					),
				) {
					Column(Modifier.padding(10.dp)) {
						Text(
							"The address bar has gone quiet",
							style = MaterialTheme.typography.titleSmall,
						)
						Text(
							"No video has been identified for the last " +
								"$tracksWithoutVideoId tracks. They were kept off-chain " +
								"because every entry requires a verified hyperlink.\n\n" +
								"Check Accessibility is still granted, or tap the " +
								"browser toolbar once to expand it.",
							style = MaterialTheme.typography.bodySmall,
						)
						Spacer(Modifier.height(6.dp))
						OutlinedButton(onClick = onOpenAccessibility) { Text("Accessibility") }
					}
				}
			}

			// Five tabs no longer fit a phone's width, so the strip scrolls
			// rather than silently clipping whichever one is last.
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.padding(vertical = 8.dp)
					.horizontalScroll(rememberScrollState()),
			) {
				OutlinedButton(onClick = { tab = 0 }) {
					Text(if (tab == 0) "▸ Now (${sessions.size})" else "Now (${sessions.size})")
				}
				OutlinedButton(onClick = { tab = 1 }) {
					Text(if (tab == 1) "▸ Account" else "Account")
				}
				OutlinedButton(onClick = { tab = 2 }) {
					Text(if (tab == 2) "▸ History (${recent.size})" else "History (${recent.size})")
				}
				OutlinedButton(onClick = { tab = 3 }) {
					Text(
						if (tab == 3) {
							"▸ Not logged (${skipped.size})"
						} else {
							"Not logged (${skipped.size})"
						},
					)
				}
				OutlinedButton(onClick = { tab = 4 }) {
					Text(if (tab == 4) "▸ Log" else "Log")
				}
				// No weight spacer: inside a scrolling row the width is
				// unbounded, and `weight` can't resolve against infinity.
				if (tab == 4) OutlinedButton(onClick = onExportLog) { Text("Export") }
			}

			when (tab) {
					0 -> SessionList(
						sessions = sessions,
						monitoring = monitoring,
						thresholdPercent = thresholdPercent,
						canBroadcast = account != null,
					busy = accountBusy,
					lookupsOn = enrichment && urlWatcherEnabled,
					onBroadcast = onBroadcastSession,
				)
				1 -> AccountTab(
					account = account,
					busy = accountBusy,
					status = accountStatus,
					statusIsError = accountStatusIsError,
					onValidateAndSave = onValidateAndSave,
					onForget = onForgetKey,
					onBroadcastTest = onBroadcastTest,
				)

				2 -> HistoryList(recent, mutedIds, onMute)
				3 -> SkippedList(skipped)
				else -> LogList(logLines, onClearLog)
			}
		}
	}
}

/**
 * Two nested switches, and the nesting is the point.
 *
 * **Monitoring** is the outer one: off means the probe is gone and nothing is
 * read at all. **Automatic scrobbling** is the inner one, and only gates the
 * final broadcast — it still leaves the app watching, which is what makes the
 * Now tab useful for diagnosing why something parsed the way it did.
 */
@Composable
private fun ScrobbleControls(
	monitoring: Boolean,
	autoScrobble: Boolean,
	thresholdPercent: Int,
	hasKey: Boolean,
	queuedCount: Int,
	urlWatcherEnabled: Boolean,
	enrichment: Boolean,
	shortClips: Boolean,
	onToggleMonitoring: (Boolean) -> Unit,
	onOpenAccessibility: () -> Unit,
	onToggleEnrichment: (Boolean) -> Unit,
	onToggleShortClips: (Boolean) -> Unit,
	onToggle: (Boolean) -> Unit,
	onRetryQueue: () -> Unit,
) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
	) {
		Column(Modifier.padding(12.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Monitoring", style = MaterialTheme.typography.titleSmall)
						Text(
							if (monitoring) {
								"Watching YouTube playback in Brave and Chrome"
						} else {
							"Stopped — no sessions or notifications are being read"
						},
						style = MaterialTheme.typography.bodySmall,
						color = if (monitoring) {
							MaterialTheme.colorScheme.onSurface
						} else {
							MaterialTheme.colorScheme.error
						},
					)
				}
				Button(
					onClick = { onToggleMonitoring(!monitoring) },
					colors = if (monitoring) {
						ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
						)
					} else {
						ButtonDefaults.buttonColors()
					},
				) {
					Text(if (monitoring) "Stop" else "Start")
				}
			}

			Spacer(Modifier.height(8.dp))
			HorizontalDivider()
			Spacer(Modifier.height(8.dp))

			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Automatic scrobbling", style = MaterialTheme.typography.titleSmall)
					Text(
						when {
							!monitoring -> "Paused while monitoring is stopped"
							!hasKey -> "Add a Hive key to enable"
							autoScrobble -> "On — scrobbles at $thresholdPercent% played"
							else -> "Off — nothing is broadcast automatically"
						},
						style = MaterialTheme.typography.bodySmall,
					)
				}
				Switch(
					checked = autoScrobble && monitoring,
					onCheckedChange = onToggle,
					enabled = hasKey && monitoring,
				)
			}
			Spacer(Modifier.height(8.dp))
			HorizontalDivider()
			Spacer(Modifier.height(8.dp))

			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Browser evidence access", style = MaterialTheme.typography.titleSmall)
					Text(
						if (urlWatcherEnabled) {
							"On — exact site/video link and visible YouTube ad labels"
						} else {
							"Off — no video link or visible ad-label detection"
						},
						style = MaterialTheme.typography.bodySmall,
					)
				}
				OutlinedButton(onClick = onOpenAccessibility) {
					Text(if (urlWatcherEnabled) "Manage" else "Enable")
				}
			}

			Spacer(Modifier.height(8.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Look videos up", style = MaterialTheme.typography.titleSmall)
					Text(
						when {
							!urlWatcherEnabled -> "Needs browser evidence access"
							enrichment -> "On — fetches the video page for artist and category"
							else -> "Off — titles are parsed on-device only"
						},
						style = MaterialTheme.typography.bodySmall,
					)
				}
				Switch(
					checked = enrichment && urlWatcherEnabled,
					onCheckedChange = onToggleEnrichment,
					enabled = urlWatcherEnabled,
				)
			}

			// The dependency is stated rather than implied. Both switches above
			// supply half the proof this rule needs — the address bar proves the
			// /shorts/ path, the lookup proves the video exists — so with either
			// off it is a no-op, and an unexplained no-op reads as a bug.
			Spacer(Modifier.height(8.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Short clips", style = MaterialTheme.typography.titleSmall)
					Text(
						when {
							!urlWatcherEnabled ->
								"Needs browser evidence access to tell a short from a video"
							!enrichment -> "Needs video lookups to confirm the clip is real"
							shortClips ->
								"On — verified shorts count from 10s; everything " +
									"else still needs 30s"
							else -> "Off — every track needs 30s to count"
						},
						style = MaterialTheme.typography.bodySmall,
					)
				}
				Switch(
					checked = shortClips && urlWatcherEnabled && enrichment,
					onCheckedChange = onToggleShortClips,
					enabled = urlWatcherEnabled && enrichment,
				)
			}

			if (queuedCount > 0) {
				Spacer(Modifier.height(8.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						"$queuedCount waiting to send",
						style = MaterialTheme.typography.bodySmall,
						modifier = Modifier.weight(1f),
					)
					OutlinedButton(onClick = onRetryQueue) { Text("Retry now") }
				}
			}
		}
	}
}

@Composable
private fun HistoryList(
	recent: List<ScrobbleEngine.ScrobbleRecord>,
	mutedIds: Set<String>,
	onMute: (ScrobbleEngine.ScrobbleRecord) -> Unit,
) {
	if (recent.isEmpty()) {
		Text(
			"No scrobbles yet.\n\nWith automatic scrobbling on, a track is " +
				"broadcast once it passes the threshold and then ends.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 24.dp),
		)
		return
	}
	LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		items(recent) { r ->
			Card(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(10.dp)) {
					Text(
						r.artist?.let { "$it — ${r.title}" } ?: r.title,
						style = MaterialTheme.typography.titleSmall,
					)
					Text(
						"${r.percentPlayed}% · ${r.status}",
						style = MaterialTheme.typography.bodySmall,
						color = when {
							r.status.startsWith("rejected") -> MaterialTheme.colorScheme.error
							r.queued -> Color(0xFFEF6C00)
							else -> Color(0xFF2E7D32)
						},
					)
					r.txId?.let {
						Text(
							"tx $it",
							fontFamily = FontFamily.Monospace,
							fontSize = 9.sp,
						)
					}
					// The escape hatch for promoted content no rule can identify.
					// Only offered where there's an id to key it on, and worded so
					// it's clear this can't undo the entry above it — nothing can.
					r.videoId?.let { id ->
						Spacer(Modifier.height(4.dp))
						if (id in mutedIds) {
							Text(
								"Muted — this video won't scrobble again",
								style = MaterialTheme.typography.bodySmall,
								color = Color(0xFF6A1B9A),
							)
						} else {
							OutlinedButton(onClick = { onMute(r) }) {
								Text("Never scrobble this again")
							}
						}
					}
				}
			}
		}
	}
}

/**
 * Tracks that finished and didn't become entries.
 *
 * The counterpart to History, and the reason it exists: without it, a strict
 * rule and a broken app look identical from the outside. Every row carries the
 * reason the engine already computed, so "where are my entries?" is answerable
 * without pulling the log off the device.
 */
@Composable
private fun SkippedList(skipped: List<ScrobbleEngine.SkipRecord>) {
	if (skipped.isEmpty()) {
		Text(
			"Nothing skipped yet.\n\nTracks that finish without being scrobbled " +
				"show up here with the reason — too short, not watched far " +
				"enough, or already logged.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 24.dp),
		)
		return
	}
	LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		items(skipped) { s ->
			Card(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(10.dp)) {
					Text(
						s.artist?.let { "$it — ${s.title}" } ?: s.title,
						style = MaterialTheme.typography.titleSmall,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						s.reason,
						style = MaterialTheme.typography.bodySmall,
						color = Color(0xFF8D6E63),
					)
					Text(
						"played ${s.playedSeconds}s" +
							(s.durationSeconds?.let { " of ${it}s" } ?: " · length unknown"),
						style = MaterialTheme.typography.bodySmall,
						fontSize = 10.sp,
					)
				}
			}
		}
	}
}

@Composable
private fun AccessBanner(onGrantAccess: () -> Unit) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.errorContainer,
		),
	) {
		Column(Modifier.padding(12.dp)) {
				Text("Notification Access required", style = MaterialTheme.typography.titleSmall)
				Text(
					"Android gates browser media-session access behind this grant. " +
						"RustedWax reads media-notification title, text, and site labels " +
						"from Brave and Chrome only; non-media notifications and every " +
						"other app are ignored.",
				style = MaterialTheme.typography.bodySmall,
			)
			Spacer(Modifier.height(8.dp))
			OutlinedButton(onClick = onGrantAccess) { Text("Open settings") }
		}
	}
}

@Composable
private fun SessionList(
	sessions: List<SessionSnapshot>,
	monitoring: Boolean,
	thresholdPercent: Int,
	canBroadcast: Boolean,
	busy: Boolean,
	lookupsOn: Boolean,
	onBroadcast: (SessionSnapshot) -> Unit,
) {
	if (!monitoring) {
		Text(
			"Monitoring is stopped.\n\nNo media sessions are being watched and no " +
				"notifications are being read. Press Start above to resume.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 24.dp),
		)
		return
	}
	if (sessions.isEmpty()) {
			Text(
				"No active media sessions.\n\nOpen youtube.com in Brave or Chrome, play a video, " +
					"then come back.",
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(top = 24.dp),
		)
		return
	}
	LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			items(sessions, key = { it.packageName + it.title }) { s ->
				SessionCard(s, thresholdPercent, canBroadcast, busy, lookupsOn, onBroadcast)
			}
	}
}

@Composable
private fun SessionCard(
	s: SessionSnapshot,
	thresholdPercent: Int,
	canBroadcast: Boolean,
	busy: Boolean,
	lookupsOn: Boolean,
	onBroadcast: (SessionSnapshot) -> Unit,
	) {
		Card(Modifier.fillMaxWidth()) {
			Column(Modifier.padding(12.dp)) {
				// Cache-only: the UI never starts network work. This is the same
				// fallback the automatic and manual paths use for missing duration.
				val facts = s.confirmed?.let { ScrobbleEngine.cachedFacts(it.videoId) }
				val effectiveDurationMs = ScrobbleBuilder.effectiveDurationMs(s, facts)
				Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					"${s.appLabel}  ·  ${s.playbackState}",
					style = MaterialTheme.typography.titleSmall,
					modifier = Modifier.weight(1f),
				)
					Verdict(s, effectiveDurationMs, facts)
			}
			Text(s.packageName, style = MaterialTheme.typography.labelSmall)

			Spacer(Modifier.height(8.dp))
			Field("title", s.title)
			Field("artist", s.artist)
			Field("album", s.album)
			Field("duration", s.durationMs?.let { fmt(it) })
			Field("position", s.positionMs?.let { fmt(it) })
			Field("played", fmt(s.playedMs))
			if (s.loopDetected) Field("loop", "detected — continuous viewing capped to one")
			s.explicitAdSignal?.let { Field("ad", "blocked — YouTube UI: \"$it\"") }
			Field(
				"browser scan",
				when {
					s.accessibilityCoverage != null -> "covered for this track"
					s.browserEvidenceEnabled -> "unavailable for this track"
					else -> "disabled — notification/lookup fallback disclosed"
				},
			)

			when (val id = s.identity) {
				is YouTubeProbe.Identity.Confirmed -> {
					Field("video id", id.videoId)
					Field("url", id.url)
					Field("proven by", id.source)
				}

				is YouTubeProbe.Identity.SiteOnly -> {
					Field("site", id.host)
					// Automatic finalization may recover an id, but no payload is
					// currently buildable and the manual button must fail closed.
					Field(
						"url",
						if (lookupsOn) {
							"— unresolved; recovery will run at finalization"
						} else {
							"— unresolved (enable lookups to attempt recovery)"
						},
					)
					Field("proven by", id.source)
				}

				is YouTubeProbe.Identity.Unconfirmed ->
					Field("youtube?", "unproven — ${id.reason}")
			}
			s.notificationHint?.let { Field("notification", it.describe()) }

			s.percentPlayed?.let { pct ->
				Spacer(Modifier.height(8.dp))
				LinearProgressIndicator(
					progress = { pct.coerceIn(0.0, 1.0).toFloat() },
					modifier = Modifier.fillMaxWidth(),
				)
					Text(
						"${(pct * 100).roundToInt()}% played — " +
							if (pct * 100 >= thresholdPercent) {
								"≥$thresholdPercent%, threshold reached"
							} else {
								"below $thresholdPercent% threshold"
							},
					style = MaterialTheme.typography.labelSmall,
				)
			}

			// What would actually be written to the chain, after title parsing.
			// Prefetched facts come from the cache so the preview and the
			// broadcast run on identical inputs — no network from the UI.
				val mb = ScrobbleEngine.cachedMusicMatch(s, facts)
			ScrobbleBuilder.from(s, facts, mb)?.let { payload ->
				Spacer(Modifier.height(8.dp))
				HorizontalDivider()
				Spacer(Modifier.height(8.dp))
				Text("payload", style = MaterialTheme.typography.labelSmall)
				Field("artist", payload.artist)
				Field("title", payload.title)
				payload.album?.let { Field("album", it) }
				Field("kind", payload.kind)
				// Why this is song or video — the part most worth checking
				// before it's on a chain that can't be edited.
				Field("kind because", ScrobbleBuilder.kindReason(s, facts, mb))
				Field("category", facts?.category ?: "— (not fetched yet)")
				Field(
					"yt music",
					when {
						facts == null -> "— (not fetched yet)"
						facts.musicVideoType != null && facts.recognisedByYouTubeMusic ->
							"✓ ${facts.musicVideoType}"
						facts.musicVideoType != null ->
							"not music evidence — ${facts.musicVideoType}"
						else -> "not in the catalogue"
					},
				)
				Field(
					"musicbrainz",
					when {
						mb == null -> "— (not checked yet)"
						mb.found -> "✓ ${mb.artist} — ${mb.title}"
						else -> "no match"
					},
				)
				// The short-clip floor turns on this, so it belongs next to the
				// other evidence rather than only in the log.
				Field(
					"listed",
					when (facts?.isUnlisted) {
						null -> "— (unknown)"
						true -> if (s.confirmed?.isShort == true) {
							"no — unlisted Short blocked"
						} else {
							"no — unlisted"
						}
						false -> "yes"
					},
				)
				Spacer(Modifier.height(8.dp))
				Button(
					// Disabled mid-send: it used to stay live, and two taps a
					// second apart put the same listen on-chain twice at 69%
					// and 70%.
					onClick = { onBroadcast(s) },
					enabled = canBroadcast && s.isTarget && !busy,
				) {
					Text(
						when {
							!canBroadcast -> "Add a key first"
							busy -> "Sending…"
							else -> "Broadcast this scrobble"
						},
					)
				}
			}

			Spacer(Modifier.height(8.dp))
			HorizontalDivider()
			Spacer(Modifier.height(8.dp))
			Text("raw metadata", style = MaterialTheme.typography.labelSmall)
			s.metadataLines.forEach {
				Text(
					it,
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun Verdict(s: SessionSnapshot, effectiveDurationMs: Long?, facts: VideoFacts?) {
	val (label, color) = when {
		// Unreachable since Phase 4 stopped watching non-browser sessions.
		// Kept as a visible tripwire: if this ever renders, the package filter
		// in SessionProbe.syncControllers has a hole.
		!s.isTarget -> "SHOULD NOT BE WATCHED" to Color(0xFFC62828)
		s.explicitAdSignal != null ->
			"blocked: YouTube UI says ad" to Color(0xFFC62828)
		s.confirmed?.isShort == true && facts?.isUnlisted == true ->
			"blocked: unlisted Short" to Color(0xFFC62828)
		s.isYouTube && !s.title.isNullOrBlank() && effectiveDurationMs != null &&
			s.confirmed != null -> "payload OK" to Color(0xFF2E7D32)
		s.isYouTube && !s.title.isNullOrBlank() && effectiveDurationMs != null ->
			"waiting for verified video id" to Color(0xFFEF6C00)
		!s.isYouTube -> "not proven YouTube" to Color(0xFFC62828)
		effectiveDurationMs == null -> "no duration" to Color(0xFFEF6C00)
		else -> "no title" to Color(0xFFEF6C00)
	}
	Text(label, color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun Field(name: String, value: String?) {
	Row(Modifier.fillMaxWidth()) {
		Text(
			name,
			style = MaterialTheme.typography.labelSmall,
			modifier = Modifier.width(96.dp),
		)
		Text(
			value ?: "—",
			style = MaterialTheme.typography.bodySmall,
			color = if (value == null) Color.Gray else MaterialTheme.colorScheme.onSurface,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun LogList(lines: List<String>, onClear: () -> Unit) {
	Column {
		OutlinedButton(onClick = onClear) { Text("Clear log") }
		Spacer(Modifier.height(4.dp))
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.background(Color(0x11000000)),
		) {
			items(lines.asReversed()) { line ->
				Text(
					line,
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					modifier = Modifier
						.fillMaxWidth()
						.horizontalScroll(rememberScrollState())
						.padding(horizontal = 4.dp, vertical = 1.dp),
					maxLines = 1,
				)
			}
		}
	}
}

private fun fmt(ms: Long): String {
	val total = ms / 1000
	return "%d:%02d".format(total / 60, total % 60)
}
