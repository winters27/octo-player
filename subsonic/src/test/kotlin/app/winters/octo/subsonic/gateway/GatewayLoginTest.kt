package app.winters.octo.subsonic.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

// HTTP Basic as RFC 7617 has it, and which kind saved headers make up.
class GatewayLoginTest {
    @Test
    fun basicIsTheUtf8OfTheNfcNameAndPassword() {
        val expected = "alice:p".toByteArray() + byteArrayOf(0xC3.toByte(), 0xA4.toByte()) + "ssword".toByteArray()
        // Composed and decomposed input give the same bytes.
        for (password in listOf("pässword", "pässword")) {
            val made = basicCredentials("alice", password)
            assertTrue(made.startsWith("Basic "))
            assertArrayEquals(expected, Base64.getDecoder().decode(made.removePrefix("Basic ")))
        }
        assertEquals("Basic YWxpY2U6cMOkc3N3b3Jk", basicCredentials("alice", "pässword"))
    }

    @Test
    fun theSavedKindComesFromTheValues() {
        assertEquals(GatewayKind.Password, savedGatewayKind(listOf("Authorization" to "Basic YWxpY2U6eA==")))
        assertEquals(GatewayKind.Password, savedGatewayKind(listOf("authorization" to "basic YWxpY2U6eA==")))
        assertEquals(GatewayKind.None, savedGatewayKind(listOf("Authorization" to "Bearer t0k")))
        assertEquals(
            GatewayKind.CloudflareToken,
            savedGatewayKind(listOf("cf-access-client-id" to "id", "CF-Access-Client-Secret" to "s", "Authorization" to "Basic YWxpY2U6eA==")),
        )
        assertEquals(GatewayKind.None, savedGatewayKind(listOf("CF-Access-Client-Id" to "id")))
        assertEquals(GatewayKind.Password, savedGatewayKind(listOf("CF-Access-Client-Id" to "id", "Authorization" to "Basic YWxpY2U6eA==")))
        assertEquals(GatewayKind.None, savedGatewayKind(emptyList()))
    }

    @Test
    fun theSavedKindsRowsAreTheOnesToHide() {
        val saved = listOf("X-Proxy-Token" to "t", "authorization" to "Basic YWxpY2U6eA==", "cf-access-client-id" to "id", "CF-Access-Client-Secret" to "s")
        assertEquals(setOf("cf-access-client-id", "CF-Access-Client-Secret"), savedGatewayNames(saved, GatewayKind.CloudflareToken))
        assertEquals(setOf("authorization"), savedGatewayNames(saved, GatewayKind.Password))
        assertEquals(emptySet<String>(), savedGatewayNames(saved, GatewayKind.None))
        assertEquals(emptySet<String>(), savedGatewayNames(listOf("Authorization" to "Bearer t0k"), GatewayKind.Password))
    }

    @Test
    fun theThreeNamesAreGatewayNamesInAnyCase() {
        assertTrue(GatewayNames.isGatewayName("authorization"))
        assertTrue(GatewayNames.isGatewayName("CF-ACCESS-CLIENT-ID"))
        assertTrue(GatewayNames.isGatewayName("cf-access-client-secret"))
        assertFalse(GatewayNames.isGatewayName("X-Proxy-Token"))
        assertFalse(GatewayNames.isGatewayName("Proxy-Authorization"))
    }
}
