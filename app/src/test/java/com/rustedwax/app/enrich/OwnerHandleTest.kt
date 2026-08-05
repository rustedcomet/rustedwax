package com.rustedwax.app.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerHandleTest {

	@Test
	fun `canonical YouTube owner profile URLs preserve exact handle punctuation`() {
		assertEquals("@sonadarus", OwnerHandle.fromOwnerProfileUrl("http://www.youtube.com/@SonaDarus"))
		assertEquals("@status_svijet", OwnerHandle.fromOwnerProfileUrl("https://youtube.com/@Status_svijet"))
		assertEquals("@beredist", OwnerHandle.fromOwnerProfileUrl("https://www.youtube.com/@Beredist/"))
		assertTrue(OwnerHandle.matches("@Status_svijet", "status_SVIJET"))
		assertFalse(OwnerHandle.matches("@Status_svijet", "@Status-svijet"))
	}

	@Test
	fun `host confusion credentials ports queries fragments and extra paths refuse`() {
		listOf(
			"https://youtube.com.evil.example/@SonaDarus",
			"https://evil.example/youtube.com/@SonaDarus",
			"https://user@youtube.com/@SonaDarus",
			"https://youtube.com:443/@SonaDarus",
			"https://youtube.com/@SonaDarus/videos",
			"https://youtube.com/@SonaDarus?x=1",
			"https://youtube.com/@SonaDarus#about",
			"https://youtube.com/%40SonaDarus",
			"javascript:https://youtube.com/@SonaDarus",
			"https://youtube.com/@@SonaDarus",
		).forEach { assertNull(it, OwnerHandle.fromOwnerProfileUrl(it)) }
	}
}
