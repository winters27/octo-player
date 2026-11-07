package app.winters.octo.subsonic.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The shared answers (fixtures/gateway/vectors.json), the same file the
// iPhone app's recognizer runs, and the small readers the rules use.
class RecognizerTest {
    private val vectors = loadVectors()

    @Test
    fun everySharedAnswerIsNamedAsExpected() {
        val wrong = vectors.filter { recognizeGateway(it.facts)?.name != it.expect }
            .map { "${it.name}: got ${recognizeGateway(it.facts)}, expected ${it.expect}" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun theSharedFileHoldsEveryCase() {
        assertEquals(CASES, vectors.map { it.name }.toSet())
        assertEquals(vectors.size, CASES.size)
    }

    @Test
    fun noAnswerAtAllIsNoGateway() {
        assertNull(recognizeGateway(AnswerFacts("https://music.example.com/rest/ping.view", emptyList(), null, loginSent = true)))
    }

    @Test
    fun mediaTypesAreReadWithoutTheirParameters() {
        assertEquals("text/html", mediaType("Text/HTML; charset=utf-8"))
        assertEquals("application/json", mediaType(" application/json "))
        assertNull(mediaType(null))
        assertTrue(isHtml("text/html;charset=iso-8859-1"))
        assertFalse(isHtml("application/xhtml+xml"))
        assertFalse(isHtml(null))
    }

    @Test
    fun jsonAndBlankBodiesAreToldApart() {
        assertTrue(looksLikeJson("  \n{\"subsonic-response\":{}}"))
        assertTrue(looksLikeJson("﻿[1]"))
        assertFalse(looksLikeJson("<html>{}</html>"))
        assertFalse(looksLikeJson(""))
        assertTrue(isBlankBody(" \r\n\t"))
        assertTrue(isBlankBody(""))
        assertFalse(isBlankBody(" x "))
    }

    @Test
    fun factsNeverPrintTheSignInOrCookies() {
        val asked = "https://music.example.com/rest/ping.view?u=alice&t=secret-token"
        val facts = AnswerFacts(asked, listOf(AnswerHop(asked, 302, listOf("Set-Cookie" to "authentik_proxy_a=cookie-value"))), "<html>", true)
        assertFalse("secret-token" in facts.toString())
        assertFalse("cookie-value" in facts.toString())
    }

    private companion object {
        val CASES = setOf(
            "authentik-302-chain",
            "authentik-302-full-chain",
            "authentik-intercept-401-html",
            "authentik-cookie-on-302",
            "authentik-body-only",
            "authelia-401-location",
            "authelia-401-basic-realm",
            "authelia-session-cookie",
            "cloudflare-302-team-domain",
            "cloudflare-403-aud",
            "cloudflare-cookie-on-302",
            "plain-proxy-401-basic",
            "foreign-origin-html",
            "http-to-https-same-host",
            "same-origin-json-500",
            "json-503-authentik-cookie",
            "json-404-cf-cookie",
            "json-401-authentik-cookie",
            "authentik-cookie-on-plain-answer",
            "cf-header-on-plain-answer",
            "order-authentik-before-cloudflare",
            "order-cloudflare-before-authelia",
            "order-authelia-before-password-prompt",
            "order-password-prompt-before-sign-in-page",
            "refused-with-login-sent",
            "refused-without-login-sent",
        )
    }
}
