package com.rustedwax.app.hive

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal `condenser_api` client with node failover.
 *
 * Node list copied from the extension's `posting-key-verify.ts`, so the phone
 * talks to the same infrastructure the desktop extension already trusts.
 * Deliberately dependency-free (HttpURLConnection + org.json): the whole
 * `hive/` package stays pure JVM so it can be unit-tested without a device.
 */
class HiveRpc(private val nodes: List<String> = DEFAULT_NODES) {

	class RpcException(message: String) : Exception(message)

	/** Result of a broadcast attempt, kept structured for UI error reporting. */
	sealed interface BroadcastResult {
		data class Success(val txId: String?, val node: String) : BroadcastResult
		/** The chain rejected it — retrying won't help. */
		data class Rejected(val message: String) : BroadcastResult
		/** Every node failed for transport reasons — retrying might help. */
		data class NetworkFailure(val message: String) : BroadcastResult
	}

	data class GlobalProperties(
		val headBlockNumber: Long,
		val headBlockId: String,
		/** Chain time, not device time — expiration must be relative to this. */
		val timeEpochSec: Long,
	)

	fun getDynamicGlobalProperties(): GlobalProperties {
		val result = call("condenser_api.get_dynamic_global_properties", JSONArray())
			as? JSONObject ?: throw RpcException("unexpected DGP response")
		return GlobalProperties(
			headBlockNumber = result.getLong("head_block_number"),
			headBlockId = result.getString("head_block_id"),
			timeEpochSec = parseChainTime(result.getString("time")),
		)
	}

	/**
	 * The account's posting public keys, for validating a key the user typed.
	 * Ported from `fetchPostingPubkeys` in `posting-key-verify.ts`.
	 */
	fun getPostingPublicKeys(username: String): List<String> {
		val params = JSONArray().put(JSONArray().put(username))
		val result = call("condenser_api.get_accounts", params) as? JSONArray
			?: throw RpcException("unexpected get_accounts response")
		if (result.length() == 0) return emptyList()
		val auths = result.getJSONObject(0)
			.getJSONObject("posting")
			.getJSONArray("key_auths")
		return (0 until auths.length()).mapNotNull { i ->
			auths.optJSONArray(i)?.optString(0)?.takeIf { it.isNotBlank() }
		}
	}

	fun broadcast(signedTx: JSONObject): BroadcastResult {
		val params = JSONArray().put(signedTx)
		var lastTransport: String? = null
		for (node in nodes) {
			try {
				val response = post(node, "condenser_api.broadcast_transaction", params)
				response.optJSONObject("error")?.let { err ->
					// A chain-level rejection is final: bad auth, expired tx,
					// insufficient RC. Trying another node repeats the error.
					return BroadcastResult.Rejected(errorMessage(err))
				}
				val result = response.opt("result")
				val txId = (result as? JSONObject)?.optString("id")?.takeIf { it.isNotBlank() }
				return BroadcastResult.Success(txId, node)
			} catch (e: Exception) {
				lastTransport = "${node.substringAfter("//")}: ${e.message}"
			}
		}
		return BroadcastResult.NetworkFailure(lastTransport ?: "all nodes unreachable")
	}

	// ── internals ──────────────────────────────────────────────────────

	private fun call(method: String, params: JSONArray): Any? {
		var lastError: Exception? = null
		for (node in nodes) {
			try {
				val response = post(node, method, params)
				response.optJSONObject("error")?.let {
					throw RpcException(errorMessage(it))
				}
				return response.opt("result")
			} catch (e: Exception) {
				lastError = e
			}
		}
		throw RpcException("all nodes failed: ${lastError?.message}")
	}

	private fun post(node: String, method: String, params: JSONArray): JSONObject {
		val body = JSONObject()
			.put("jsonrpc", "2.0")
			.put("method", method)
			.put("params", params)
			.put("id", 1)
			.toString()

		val conn = (URL(node).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = TIMEOUT_MS
			readTimeout = TIMEOUT_MS
			doOutput = true
			setRequestProperty("Content-Type", "application/json")
		}
		try {
			conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
			val text = stream?.bufferedReader()?.use { it.readText() }
				?: throw RpcException("empty response (HTTP ${conn.responseCode})")
			return JSONObject(text)
		} finally {
			conn.disconnect()
		}
	}

	private fun errorMessage(error: JSONObject): String {
		val data = error.optJSONObject("data")
		val stack = data?.optJSONArray("stack")?.optJSONObject(0)
		val detail = stack?.optString("format")?.takeIf { it.isNotBlank() }
		return detail ?: error.optString("message").takeIf { it.isNotBlank() } ?: error.toString()
	}

	/** Chain timestamps are `2026-07-23T05:47:34`, implicitly UTC. */
	private fun parseChainTime(value: String): Long {
		val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
		fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
		return (fmt.parse(value)?.time ?: throw RpcException("bad chain time: $value")) / 1000
	}

	companion object {
		private const val TIMEOUT_MS = 8000

		val DEFAULT_NODES = listOf(
			"https://api.openhive.network",
			"https://hive-api.arcange.eu",
			"https://api.hive.blog",
		)
	}
}
