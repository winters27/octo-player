package app.winters.octo.server

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import okio.Buffer
import okio.FileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A cover kept from a sign-in page before such answers were refused is told
// from a picture by its first bytes, and a picture is left whole.
class CoverHtmlTest {
    private fun kept(bytes: ByteArray) = SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), "image/jpeg", DataSource.DISK)

    // Red when startsLikeHtml misses a BOM or leading whitespace.
    @Test
    fun aPageKeptAsACoverIsToldFromAPicture() {
        assertTrue(startsLikeHtml(kept("﻿ \n<!DOCTYPE html><html>".toByteArray())))
        assertTrue(startsLikeHtml(kept("<html aria-label=\"authentik\">".toByteArray())))
    }

    // Red when startsLikeHtml reads the cover instead of peeking.
    @Test
    fun aPictureIsLeftWholeForTheDecoder() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(5_000) { 3 }
        val result = kept(jpeg)
        assertFalse(startsLikeHtml(result))
        assertArrayEquals(jpeg, result.source.source().readByteArray())
    }
}
