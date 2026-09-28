package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Browsing the server by folder.
class FoldersClientTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(folder: String? = null) =
        SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient(), musicFolderId = folder)

    private fun answer(name: String) = server.enqueue(
        MockResponse.Builder()
            .body(requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8))
            .build(),
    )

    @Test
    fun rootListsEveryLetterGroupThenLooseSongs() = runTest {
        answer("getIndexes")
        val root = client().indexes()

        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/getIndexes"))
        assertNull(url.queryParameter("musicFolderId"))
        assertEquals(listOf("Kavinsky", "Massive Attack"), root.folders.map { it.name })
        // A numeric id is kept as text.
        assertEquals("1234", root.folders[1].id)
        assertEquals(listOf("Loose Song"), root.songs.map { it.title })
    }

    @Test
    fun rootHonoursTheChosenLibraryFolder() = runTest {
        answer("getIndexes")
        client(folder = "3").indexes()
        assertEquals("3", server.takeRequest().url.queryParameter("musicFolderId"))
    }

    @Test
    fun directorySplitsFoldersFromSongs() = runTest {
        answer("getMusicDirectory")
        val dir = client().musicDirectory("1mNJ8hAlbt4jJc2dFaf38y")

        val url = server.takeRequest().url
        assertTrue(url.encodedPath.endsWith("/rest/getMusicDirectory"))
        assertEquals("1mNJ8hAlbt4jJc2dFaf38y", url.queryParameter("id"))
        assertEquals("Kavinsky", dir.name)
        // A folder is named by its title, or its name when there is no title.
        assertEquals(listOf("Nightcall", "OutRun"), dir.folders.map { it.name })
        // A child with no id is skipped.
        assertEquals(listOf("XEjJFBng9tY4swUvEp7Wj7"), dir.songs.map { it.id })
        assertEquals(179, dir.songs[0].duration)
        // The folder it is in, the cover a folder has, and each song's own
        // folder, for "Show in folder" and breadcrumbs.
        assertEquals("root", dir.parent)
        assertEquals("al-7wsVynyqPtHmsFWz6wUDCj", dir.folders[0].coverArt)
        assertNull(dir.folders[1].coverArt)
        assertEquals("1mNJ8hAlbt4jJc2dFaf38y", dir.songs[0].parent)
    }

    @Test
    fun aNumberedParentAndSongCountsAreRead() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"subsonic-response":{"status":"ok","directory":{"id":12,"name":"Albums","parent":3,"child":[
                {"id":13,"isDir":true,"title":"Kid A","songCount":10},
                {"id":"s1","isDir":false,"title":"Idioteque","parent":"12","path":"Radiohead/Kid A/08 Idioteque.flac"}]}}}""",
            ).build(),
        )
        val dir = client().musicDirectory("12")
        assertEquals("3", dir.parent)
        assertEquals(10, dir.folders.single().songCount)
        assertEquals("Radiohead/Kid A/08 Idioteque.flac", dir.songs.single().path)
    }

    @Test
    fun aFolderWithNoParentSaysNone() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","directory":{"id":"top","name":"Top"}}}""").build())
        assertNull(client().musicDirectory("top").parent)
    }

    @Test
    fun anEmptyRootIsNotAnError() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build())
        val root = client().indexes()
        assertTrue(root.folders.isEmpty())
        assertTrue(root.songs.isEmpty())
    }
}
