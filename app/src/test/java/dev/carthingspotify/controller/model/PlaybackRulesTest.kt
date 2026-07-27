package dev.carthingspotify.controller.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRulesTest {
    @Test fun `repeat cycles context track and off`() {
        assertEquals("context", PlaybackRules.nextRepeat("off"))
        assertEquals("track", PlaybackRules.nextRepeat("context"))
        assertEquals("off", PlaybackRules.nextRepeat("track"))
    }

    @Test fun `track selection preserves a playlist or album context`() {
        assertEquals(
            "spotify:playlist:demo",
            PlaybackRules.contextForSelection("spotify:playlist:demo", "spotify:track:song")
        )
        assertEquals(
            "spotify:album:demo",
            PlaybackRules.contextForSelection("spotify:album:demo", "spotify:track:song")
        )
    }

    @Test fun `selection refuses to replace a queue without a compatible context`() {
        assertNull(PlaybackRules.contextForSelection("", "spotify:track:song"))
        assertNull(PlaybackRules.contextForSelection("spotify:artist:demo", "spotify:track:song"))
        assertNull(PlaybackRules.contextForSelection("spotify:playlist:demo", "spotify:episode:show"))
    }
}
