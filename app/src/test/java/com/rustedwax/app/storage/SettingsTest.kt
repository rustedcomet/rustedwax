package com.rustedwax.app.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

	private class MemoryStore : SettingsStore {
		private val values = mutableMapOf<String, Any>()

		override fun getBoolean(key: String, default: Boolean): Boolean =
			values[key] as? Boolean ?: default

		override fun putBoolean(key: String, value: Boolean) {
			values[key] = value
		}

		override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

		override fun putInt(key: String, value: Int) {
			values[key] = value
		}
	}

	@Test
	fun `both native app settings default off`() {
		val settings = Settings(MemoryStore())
		assertFalse(settings.nativeYouTube)
		assertFalse(settings.nativeYouTubeMusic)
	}

	@Test
	fun `native app settings persist separately`() {
		val store = MemoryStore()
		Settings(store).nativeYouTube = true

		var reloaded = Settings(store)
		assertTrue(reloaded.nativeYouTube)
		assertFalse(reloaded.nativeYouTubeMusic)

		reloaded.nativeYouTube = false
		reloaded.nativeYouTubeMusic = true
		reloaded = Settings(store)
		assertFalse(reloaded.nativeYouTube)
		assertTrue(reloaded.nativeYouTubeMusic)
	}
}
