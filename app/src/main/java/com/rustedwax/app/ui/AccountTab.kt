package com.rustedwax.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rustedwax.app.storage.KeyVault

/**
 * Phase 1 UI: enter a Hive username and posting key, validate against the
 * account's on-chain posting authority, store it encrypted.
 *
 * The key field is masked and the stored key is never displayed again — only
 * the derived public key, which is public by definition.
 */
@Composable
fun AccountTab(
	account: KeyVault.Account?,
	busy: Boolean,
	status: String?,
	statusIsError: Boolean,
	onValidateAndSave: (username: String, wif: String) -> Unit,
	onForget: () -> Unit,
	onBroadcastTest: () -> Unit,
) {
	Column(Modifier.verticalScroll(rememberScrollState())) {
		if (account != null) {
			SavedAccount(account, busy, onForget, onBroadcastTest)
		} else {
			KeyEntry(busy, onValidateAndSave)
		}

		status?.let {
			Spacer(Modifier.height(12.dp))
			Text(
				it,
				style = MaterialTheme.typography.bodySmall,
				color = if (statusIsError) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurface
				},
			)
		}

		Spacer(Modifier.height(16.dp))
		SecurityNote()
		Spacer(Modifier.height(12.dp))
		AffiliationNote()
		Spacer(Modifier.height(24.dp))
	}
}

@Composable
private fun KeyEntry(busy: Boolean, onValidateAndSave: (String, String) -> Unit) {
	var username by remember { mutableStateOf("") }
	var wif by remember { mutableStateOf("") }

	Column {
		Text("Connect your Hive account", style = MaterialTheme.typography.titleMedium)
		Spacer(Modifier.height(4.dp))
		Text(
			"Brave on Android can't run Hive Keychain, so this app signs " +
				"scrobbles itself. The key is checked against your account's " +
				"on-chain posting authority before it's accepted.",
			style = MaterialTheme.typography.bodySmall,
		)
		Spacer(Modifier.height(16.dp))

		OutlinedTextField(
			value = username,
			onValueChange = { username = it },
			label = { Text("Hive username") },
			placeholder = { Text("without the @") },
			singleLine = true,
			enabled = !busy,
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(8.dp))
		OutlinedTextField(
			value = wif,
			onValueChange = { wif = it },
			label = { Text("Posting key") },
			placeholder = { Text("5…") },
			singleLine = true,
			enabled = !busy,
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(12.dp))
		Row {
			Button(
				onClick = { onValidateAndSave(username, wif) },
				enabled = !busy && username.isNotBlank() && wif.isNotBlank(),
			) {
				Text("Validate & save")
			}
			if (busy) {
				Spacer(Modifier.height(8.dp))
				CircularProgressIndicator(Modifier.padding(start = 12.dp).height(24.dp))
			}
		}
	}
}

@Composable
private fun SavedAccount(
	account: KeyVault.Account,
	busy: Boolean,
	onForget: () -> Unit,
	onBroadcastTest: () -> Unit,
) {
	Column {
		Text("Connected", style = MaterialTheme.typography.titleMedium)
		Spacer(Modifier.height(8.dp))
		Card(Modifier.fillMaxWidth()) {
			Column(Modifier.padding(12.dp)) {
				Text("@${account.username}", style = MaterialTheme.typography.titleSmall)
				Spacer(Modifier.height(4.dp))
				Text("posting key in use:", style = MaterialTheme.typography.labelSmall)
				Text(
					account.publicKey,
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
				)
			}
		}
		Spacer(Modifier.height(12.dp))
		Row {
			Button(onClick = onBroadcastTest, enabled = !busy) {
				Text("Broadcast a test scrobble")
			}
			Spacer(Modifier.height(8.dp))
			OutlinedButton(
				onClick = onForget,
				enabled = !busy,
				modifier = Modifier.padding(start = 8.dp),
			) {
				Text("Forget key")
			}
		}
		if (busy) {
			Spacer(Modifier.height(12.dp))
			CircularProgressIndicator(Modifier.height(24.dp))
		}
	}
}

/**
 * Stated in the app, not only the README — someone handed this APK has no
 * reason to know it isn't an official client, and the on-chain entries it
 * writes are indistinguishable from ones written by the real thing.
 */
@Composable
private fun AffiliationNote() {
	Column {
		Text("Unofficial and unsupported", style = MaterialTheme.typography.titleSmall)
		Spacer(Modifier.height(4.dp))
		Text(
			"RustedWax is a personal project. It is not part of scrobble.life, " +
				"Hive Scrobbler or Web Scrobbler, is not endorsed by them, and is " +
				"not supported by them.\n\n" +
				"It writes the same on-chain format, so what it posts appears " +
				"alongside entries from those apps — but if something here " +
				"misbehaves, it's this app's fault, not theirs. Don't take " +
				"RustedWax problems to them.",
			style = MaterialTheme.typography.bodySmall,
		)
	}
}

@Composable
private fun SecurityNote() {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant,
		),
	) {
		Column(Modifier.padding(12.dp)) {
			Text("About your posting key", style = MaterialTheme.typography.titleSmall)
			Spacer(Modifier.height(4.dp))
			Text(
				"It's stored encrypted under the Android Keystore and never leaves " +
					"the device — only signed transactions are sent to a Hive node.\n\n" +
					"But a posting key can post, comment and vote as you, with no " +
					"per-action prompt. Consider adding a dedicated key to your posting " +
					"authority from desktop Keychain and using that one here, so you can " +
					"revoke it without rotating the key you use everywhere else.\n\n" +
					"This app never asks for your owner, active or memo key.",
				style = MaterialTheme.typography.bodySmall,
			)
		}
	}
}
