package app.winters.octo.sort

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouriteSortTest {
    private data class Fav(val id: String, val name: String, val likedAt: Long)

    private val favs = listOf(Fav("1", "beta", 300), Fav("2", "alpha", 100), Fav("3", "gamma", 200), Fav("4", "alpha", 200))

    private fun sorted(order: SortOrder) = sortFavourites(favs, order, { it.likedAt }, { it.name }, { it.id }).map { it.id }

    @Test
    fun startsWithTheNewestFavourite() {
        assertEquals(SortOrder(FavouriteSort.DateAdded, descending = true), SortList.Favourites.default)
        // Two at the same time go by name.
        assertEquals(listOf("1", "4", "3", "2"), sorted(SortList.Favourites.default))
        assertEquals(listOf("2", "4", "3", "1"), sorted(SortOrder(FavouriteSort.DateAdded, descending = false)))
    }

    @Test
    fun byNameRunsAToZThenBack() {
        assertEquals(listOf("2", "4", "1", "3"), sorted(SortOrder(FavouriteSort.Name, descending = false)))
        assertEquals(listOf("3", "1", "2", "4"), sorted(SortOrder(FavouriteSort.Name, descending = true)))
    }

    @Test
    fun theFavouritesPageOffersBoth() {
        assertEquals(listOf("Date added", "Name"), SortList.Favourites.options.map { it.label })
    }
}
