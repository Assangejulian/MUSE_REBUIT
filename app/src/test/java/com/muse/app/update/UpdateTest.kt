package com.muse.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test
    fun newerVersionDetectsBump() {
        assertTrue(isNewerVersion("0.1.1", "0.1.0"))
        assertTrue(isNewerVersion("v0.2.0", "0.1.9"))
        assertFalse(isNewerVersion("0.1.1", "0.1.1"))
        assertFalse(isNewerVersion("0.1.0", "0.1.1"))
        assertTrue(isNewerVersion("0.1.1-debug", "0.1.0"))
    }

    @Test
    fun parseReleasePicksApk() {
        val json = """
            {
              "tag_name": "v0.1.1",
              "body": "fix tools type",
              "html_url": "https://github.com/Assangejulian/MUSE_REBUIT/releases/tag/v0.1.1",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt", "size": 1},
                {"name": "Muse-0.1.1-debug.apk", "browser_download_url": "https://example.com/Muse-0.1.1-debug.apk", "size": 12, "digest": "sha256:abc"}
              ]
            }
        """.trimIndent()
        val release = parseLatestRelease(json)!!
        assertEquals("v0.1.1", release.tag)
        assertEquals("0.1.1", release.version)
        assertEquals("Muse-0.1.1-debug.apk", release.apkName)
        assertEquals("abc", release.sha256)
    }
}
