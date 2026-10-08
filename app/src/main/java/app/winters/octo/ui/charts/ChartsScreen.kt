package app.winters.octo.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.discovery.ChartList
import app.winters.octo.discovery.playsText
import app.winters.octo.discovery.rankedBy
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.SongLead
import app.winters.octo.ui.common.SongRow
import app.winters.octo.ui.common.rowAlbum
import app.winters.octo.ui.common.screenPadding
import app.winters.octo.ui.library.FilterChip
import java.util.Locale

// The charts and new songs an Octo server reads from Apple Music, one at a
// time, picked with the chips: the chart country's most played songs, Best
// New Songs, Trending Songs and a chart per genre. A song the library has
// plays from it; any other has the add button at the end of its row.
@Composable
fun ChartsScreen(onBack: () -> Unit, vm: ChartsViewModel = hiltViewModel()) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "title") { ScreenTitle("Charts") }
            if (vm.choices.isNotEmpty()) {
                item(key = "chips") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    ) {
                        items(vm.choices, key = { it.id }) { choice ->
                            FilterChip(choice.label, choice.id == vm.selected) { vm.pick(choice.id) }
                        }
                    }
                }
            }
            val list = vm.list
            when {
                vm.none -> item(key = "none") { Quiet("This server doesn't have charts.") }
                list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = OctoColors.TextSecondary)
                    }
                }
                list.songs.isEmpty() -> item(key = "empty") { Quiet("Nothing in this chart right now. Try another.") }
                else -> {
                    caption(list)?.let { item(key = "about") { Quiet(it) } }
                    item(key = "play") {
                        GlazeButton("Play all", onClick = { vm.play(0) }, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                    }
                    itemsIndexed(list.songs, key = { _, ranked -> "chart:${list.chart}:${ranked.track.id}" }) { index, ranked ->
                        SongRow(
                            ranked.track,
                            SongLead.Ranked(ranked.rank),
                            subtitle = { shown ->
                                listOfNotNull(
                                    shown.artist.ifEmpty { null },
                                    rowAlbum(shown).ifEmpty { null },
                                    ranked.plays?.let(::playsText),
                                ).joinToString(" • ")
                            },
                            offerAdd = true,
                        ) { vm.play(index) }
                    }
                }
            }
        }
        BackButton(onBack)
    }
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = OctoType.caption,
        color = OctoColors.TextMuted,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

// Who ranked the chart and for which country: "Most played on Apple Music
// in United States".
internal fun caption(list: ChartList): String? {
    val by = rankedBy(list.source, list.chart) ?: return null
    val country = list.country?.takeIf { it.length == 2 }?.let { Locale("", it.uppercase()).displayCountry }
    return if (country.isNullOrBlank()) by else "$by in $country"
}
