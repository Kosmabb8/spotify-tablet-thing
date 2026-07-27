package dev.carthingspotify.controller.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun `RFC 7636 challenge is generated correctly`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge(verifier))
    }

    @Test fun `verifier has legal PKCE length and alphabet`() {
        val verifier = Pkce.newVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
    }
}
