package com.rustedwax.app.detect

/**
 * Distinguishes an accessibility grant that was never given from one that was
 * given and has since gone away.
 *
 * ## Why this exists
 *
 * Android removes a crashed accessibility service from
 * `enabled_accessibility_services`. From the app's side that is byte-identical
 * to the user switching it off, so a single boolean cannot tell the two apart —
 * and the honest message for each is completely different.
 *
 * On 2026-08-05 the browser watcher was dropped exactly that way. It stayed off
 * for most of a day, the Settings row said "Off" as though that were a choice,
 * and roughly half that day's YouTube watch history never reached the app.
 * `dumpsys accessibility` listed it under `crashed services`, but nothing the
 * owner could see did.
 *
 * The evidence is therefore "was it ever live", which only a live service can
 * establish and which no crash can retract.
 */
enum class GrantHealth {
	/** Granted and receiving events. */
	LIVE,

	/** Never granted on this install. Asking the user to enable it is correct. */
	NEVER_GRANTED,

	/**
	 * Granted at some point and not enabled now. Either the user revoked it or
	 * the service crashed and Android disabled it; the app cannot tell which,
	 * so it must not claim the user did it.
	 */
	DROPPED,
}

object AccessibilityGrantHealth {

	/**
	 * @param live whether the grant is enabled *and* the accessibility master
	 * switch is on, per the service's own `isEnabled`.
	 * @param everGranted whether [live] has ever been observed true and
	 * remembered across restarts.
	 */
	fun classify(live: Boolean, everGranted: Boolean): GrantHealth = when {
		live -> GrantHealth.LIVE
		everGranted -> GrantHealth.DROPPED
		else -> GrantHealth.NEVER_GRANTED
	}
}
