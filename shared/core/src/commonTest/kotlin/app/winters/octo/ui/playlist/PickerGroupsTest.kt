package app.winters.octo.ui.playlist

import app.winters.octo.playlists.PlaylistFolderTree
import app.winters.octo.playlists.PlaylistFolderWords
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistPlace
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PickerGroupsTest {
    private data class P(val id: String, val name: String, val upkeep: Boolean = false, val refuses: Boolean = false)

    private val tree = PlaylistFolderTree(
        listOf(PlaylistFolder("road", "Road trips"), PlaylistFolder("summer", "Summer", "road"), PlaylistFolder("art", "Art")),
        listOf(PlaylistPlace("p3", "summer"), PlaylistPlace("p4", "road"), PlaylistPlace("p5", "art"), PlaylistPlace("u1", "art")),
        Locale.ENGLISH,
    )

    private val all = listOf(
        P("p1", "zebra"), P("p2", "Apple"), P("p3", "Beach"), P("p4", "Coast"), P("p5", "Murals"),
        P("u1", "🛠 Remove from library", upkeep = true, refuses = true), P("u2", "Request a copy", upkeep = true),
    )

    private fun build(forFind: Boolean, recent: List<String> = listOf("p5", "p1", "u1", "p2")) = PickerGroups.build(
        all, { it.id }, { it.id }, { it.name }, { it.upkeep }, { it.refuses }, recent, tree, forFind, Locale.ENGLISH,
    )

    @Test
    fun recentThenUnfiledThenEachFolderThenUpkeep() {
        val groups = build(forFind = false)
        assertEquals(
            listOf(
                PickerGroupKind.Recent to null,
                PickerGroupKind.Unfiled to null,
                PickerGroupKind.Folder to "Art",
                PickerGroupKind.Folder to "Road trips",
                PickerGroupKind.Folder to "Road trips / Summer",
                PickerGroupKind.Upkeep to PlaylistFolderWords.LIBRARY_UPKEEP,
            ),
            groups.map { it.kind to it.title },
        )
        assertEquals(listOf("p5", "p1", "u1"), groups[0].items.map { it.id }, "three recent, in the order they were used")
        assertEquals(listOf("Apple", "zebra"), groups[1].items.map { it.name })
        // Where a name led by a symbol sorts is the platform collator's
        // choice, so only which lists are in the group is asserted.
        assertEquals(setOf("u1", "u2"), groups.last().items.map { it.id }.toSet(), "upkeep never inside a folder, even when placed")
    }

    @Test
    fun aFindLeavesOutTheUpkeepListsThatRefuseIt() {
        val groups = build(forFind = true)
        assertEquals(listOf("p5", "p1", "p2"), groups[0].items.map { it.id })
        assertEquals(listOf("u2"), groups.last().items.map { it.id })
    }

    @Test
    fun emptyGroupsAreLeftOutAndNoTreeMeansNoFolders() {
        val groups = PickerGroups.build(listOf(P("p1", "One")), { it.id }, { it.id }, { it.name }, { it.upkeep }, { it.refuses }, emptyList(), null, false, Locale.ENGLISH)
        assertEquals(listOf(PickerGroupKind.Unfiled), groups.map { it.kind })
    }
}
