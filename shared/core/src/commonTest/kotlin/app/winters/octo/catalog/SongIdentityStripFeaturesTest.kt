package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

// A title without its guest credits, the same as on the server: the credit
// goes, and a version written beside it stays, whether it shares the
// credit's bracket, follows it, or comes before it after a dash.
class SongIdentityStripFeaturesTest {
    @Test
    fun aGuestCreditInsideAVersionTagKeepsTheVersion() {
        val cases = listOf(
            "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)" to "Get Lucky (Radio Edit)",
            "Get Lucky [Radio Edit - feat. Pharrell Williams and Nile Rodgers]" to "Get Lucky [Radio Edit]",
            "Get Lucky (Radio Edit, feat. Pharrell Williams & Nile Rodgers)" to "Get Lucky (Radio Edit)",
            "Get Lucky (Radio Edit – feat. Pharrell Williams)" to "Get Lucky (Radio Edit)",
            "Get Lucky (Radio Edit / ft. Pharrell Williams)" to "Get Lucky (Radio Edit)",
            "Get Lucky (Radio Edit featuring Pharrell Williams)" to "Get Lucky (Radio Edit)",
            "Get Lucky - Radio Edit - feat. Pharrell Williams and Nile Rodgers" to "Get Lucky - Radio Edit",
            "Get Lucky (feat. Pharrell Williams and Nile Rodgers) - Radio Edit" to "Get Lucky - Radio Edit",
            "Get Lucky feat. Pharrell Williams and Nile Rodgers (Radio Edit)" to "Get Lucky (Radio Edit)",
        )
        for ((title, expected) in cases) assertEquals(title, expected, SongIdentity.stripFeatures(title))
    }

    @Test
    fun aCreditAloneGoesAndEverythingElseStays() {
        val cases = listOf(
            "Get Lucky (feat. Pharrell Williams)" to "Get Lucky",
            "Get Lucky feat. Pharrell Williams" to "Get Lucky",
            "Get Lucky (Live)" to "Get Lucky (Live)",
            "Get Lucky (Daft Punk Remix) [feat. Pharrell Williams]" to "Get Lucky (Daft Punk Remix)",
            "Get Lucky" to "Get Lucky",
            "" to "",
        )
        for ((title, expected) in cases) assertEquals(title, expected, SongIdentity.stripFeatures(title))
    }
}
