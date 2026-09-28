package app.winters.octo.desktop.home

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AlbumShelvesTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()

    // A played-through album from last week, one started last month, one
    // played a year ago, and two never played, one added lately.
    private val index = LibraryIndex(
        songs = listOf(
            Song("f1", albumId = "full", playCount = 4, played = "2026-09-20T10:00:00Z"),
            Song("f2", albumId = "full", playCount = 4, played = "2026-09-21T10:00:00Z"),
            Song("h1", albumId = "half", playCount = 1, played = "2026-08-20T10:00:00Z"),
            Song("h2", albumId = "half"),
            Song("o1", albumId = "old", playCount = 9, played = "2025-09-01T10:00:00Z"),
            Song("n1", albumId = "new"),
            Song("u1", albumId = "untouched"),
        ),
        albums = listOf(
            Album("full", "Full"),
            Album("half", "Half"),
            Album("old", "Old"),
            Album("new", "New", created = "2026-09-25T00:00:00Z"),
            Album("untouched", "Untouched", created = "2024-01-01T00:00:00Z"),
        ),
        artists = emptyList(),
    )

    private fun on(shelf: AlbumShelf) = albumsOn(shelf, index, now).map { it.id }

    @Test
    fun eachShelfAsksItsOwnQuestionOfTheLibrary() {
        assertEquals(listOf("old", "full", "half"), on(AlbumShelf.MostPlayed))
        assertEquals(listOf("old"), on(AlbumShelf.NotPlayedLately))
        assertEquals(listOf("half"), on(AlbumShelf.NeverFinished))
        assertEquals(listOf("new", "untouched"), on(AlbumShelf.NeverPlayed))
    }

    @Test
    fun homeWorksOutItsShelvesOnceForEachLibraryRead(): Unit = runBlocking {
        FakeServer().use { server -> checkRediscovery(HomeStore(server.connection(), CoroutineScope(SupervisorJob() + Dispatchers.Default), clock = { now })) }
    }

    private suspend fun checkRediscovery(store: HomeStore) {
        store.rediscover(index)
        withTimeout(5_000) { while (store.rediscovered == null) yield() }
        val found = store.rediscovered!!
        assertEquals(listOf("old"), found.notPlayedLately.map { it.id })
        assertEquals(listOf("half"), found.neverFinished.map { it.id })
        assertEquals(listOf("new", "untouched"), found.neverPlayed.map { it.id })
        store.rediscover(index)
        assertEquals("the same library is not worked through again", found, store.rediscovered)
    }
}
