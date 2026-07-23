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
import com.rustedwax.app.detect.ProbeHolder
import com.rustedwax.app.detect.ScrobbleBuilder
import com.rustedwax.app.detect.SessionProbe
import com.rustedwax.app.detect.EventLog
import com.rustedwax.app.storage.KeyVault
import com.rustedwax.app.storage.Settings
import com.rustedwax.app.ui.MainScreen

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
		ScrobbleEngine.init(this)
		vault = KeyVault(this)
		settings = Settings(this)

		setContent {
			MaterialTheme {
				val probe by ProbeHolder.probe.collectAsStateWithLifecycle()
				val logLines by EventLog.lines.collectAsStateWithLifecycle()
				val recent by ScrobbleEngine.recent.collectAsStateWithLifecycle()
				val queued by ScrobbleEngine.queueSize.collectAsStateWithLifecycle()

				var sessions by remember { mutableStateOf(emptyList<com.rustedwax.app.detect.SessionSnapshot>()) }
				var hasAccess by remember { mutableStateOf(SessionProbe.hasNotificationAccess(this)) }
				var account by remember { mutableStateOf(vault.account) }
				var autoScrobble by remember { mutableStateOf(settings.autoScrobble) }
				var busy by remember { mutableStateOf(false) }
				var status by remember { mutableStateOf<String?>(null) }
				var statusIsError by remember { mutableStateOf(false) }

				// The probe belongs to the service; poll it for live position
				// and re-check the grant, which can be revoked from Settings.
				LaunchedEffect(probe) {
					while (true) {
						hasAccess = SessionProbe.hasNotificationAccess(this@MainActivity)
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
					error = probe?.error?.value,
					account = account,
					accountBusy = busy,
					accountStatus = status,
					accountStatusIsError = statusIsError,
					autoScrobble = autoScrobble,
					thresholdPercent = settings.thresholdPercent,
					recent = recent,
					queuedCount = queued,
					onGrantAccess = ::openNotificationAccessSettings,
					onExportLog = ::exportLog,
					onClearLog = EventLog::clear,
					onToggleAutoScrobble = { enabled ->
						if (enabled && account == null) {
							report("Add a Hive key first — nothing can be signed.", true)
						} else {
							ScrobbleEngine.setAutoScrobble(enabled)
							autoScrobble = enabled
						}
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
						val payload = ScrobbleBuilder.from(session)
						if (payload == null) {
							report("Nothing broadcastable in that session yet.", isError = true)
						} else {
							busy = true
							broadcast(payload) { msg, err ->
								busy = false
								report(msg, err)
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
		onDone: (message: String, isError: Boolean) -> Unit,
	) {
		val saved = vault.account
		val key = vault.loadKey()
		if (saved == null || key == null) {
			onDone("No key saved — add one on the Account tab first.", true)
			return
		}

		EventLog.append("hive", "broadcasting as @${saved.username}: ${payload.toJson()}")

		lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching { broadcaster.broadcastScrobble(saved.username, key, payload) }
					.getOrElse { HiveRpc.BroadcastResult.NetworkFailure(it.message ?: "unknown") }
			}
			when (result) {
				is HiveRpc.BroadcastResult.Success -> onDone(
					"Broadcast accepted by ${result.node.substringAfter("//")}" +
						(result.txId?.let { " — tx $it" } ?: ""),
					false,
				)

				is HiveRpc.BroadcastResult.Rejected ->
					onDone("Chain rejected it: ${result.message}", true)

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
