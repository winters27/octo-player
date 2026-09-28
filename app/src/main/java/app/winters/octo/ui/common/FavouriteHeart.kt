package app.winters.octo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.design.GlowIcon
import app.winters.octo.design.IconAccent
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.rememberIconAccent
import app.winters.octo.favourites.FavouriteStore
import app.winters.octo.listening.FavouriteKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FavouriteHeartViewModel @Inject constructor(val favourites: FavouriteStore) : ViewModel()

// The heart on an album or artist page, drawn like the player's: it glows
// white while the album or artist is a favourite. A tap adds or removes it.
@Composable
fun FavouriteHeart(kind: FavouriteKind, id: String, modifier: Modifier = Modifier, vm: FavouriteHeartViewModel = hiltViewModel()) {
    val ids by vm.favourites.ids(kind).collectAsStateWithLifecycle()
    val on = id in ids
    // The heart beats once as it is added.
    val pulse = rememberIconAccent(IconAccent.Pulse)
    Box(
        modifier
            .size(44.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                if (!on) pulse.play()
                vm.favourites.toggle(kind, id)
            }
            .semantics {
                contentDescription = "Favourite"
                stateDescription = if (on) "In favourites" else "Not in favourites"
            },
        contentAlignment = Alignment.Center,
    ) {
        GlowIcon(
            painterResource(if (on) OctoIcons.Liked else OctoIcons.Like),
            tint = if (on) Color.White else Color.White.copy(alpha = 0.6f),
            lit = on,
            modifier = Modifier.size(24.dp),
            accent = pulse,
        )
    }
}
