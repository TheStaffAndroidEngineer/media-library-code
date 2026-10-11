package staff.android.shelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject

const val ABOUT="Shelf plays an original local film with Media3 and verifies saved bytes before offline playback. The fixture supports immutable versions and byte ranges. It does not implement DRM, adaptive streaming or background playback."
fun validateView(data:JSONObject){validateFilm(data.getJSONObject("film"))}
@Composable fun Product(state: CaseState, model: CaseModel, elapsed: Long) {
    val local by model.local.collectAsState()
    val uri by model.media.collectAsState()
    val note by model.note.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    Text("THE SHORT FILM COLLECTION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        if (maxWidth >= 640.dp && fontScale <= 1.3f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilmSurface(uri, lifecycle)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilmSummary(state, model, uri)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FilmSurface(uri, lifecycle)
                FilmSummary(state, model, uri)
            }
        }
    }
    note?.let { InfoCard("Your library", it) }
    HorizontalDivider()
    Text("On this device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (local == null) {
        Text("No saved film yet", style = MaterialTheme.typography.titleMedium)
        Text("Save the film above to make it available offline. Only a complete, verified copy appears here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val film = local!!
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ready offline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Eight seconds of stillness · version ${film.getInt("version")}")
                Text("Verified copy · ${(film.getInt("length") + 1023) / 1024} KB. A newer library version may exist.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { model.play(model.offline.uri(film)) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("offline")) { Text("Play saved film") }
                TextButton(onClick = model::remove, enabled = !state.busy && uri == null, modifier = Modifier.testTag("remove")) { Text("Remove saved film") }
                if (uri != null) Text("Close the player before removing saved media.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable private fun FilmSurface(uri: String?, lifecycle: Lifecycle.State) {
    if (uri != null && lifecycle.isAtLeast(Lifecycle.State.STARTED)) FilmPlayer(uri!!)
    else Image(painterResource(R.drawable.stillness_poster), contentDescription = "Still from the original film: an abstract study of shape and colour", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)))
}
@Composable private fun FilmSummary(state: CaseState, model: CaseModel, uri: String?) {
    Text("Eight seconds of stillness", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("8 sec · silent · original short", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (uri != null) OutlinedButton(onClick = model::stop, modifier = Modifier.fillMaxWidth().testTag("stop")) { Text("Close player") }
    state.data?.getJSONObject("film")?.let { film ->
        if (uri == null) Button(onClick = { model.play("http://10.0.2.2:8917${film.getString("path")}") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("stream")) { Text("Play from library") }
        OutlinedButton(onClick = model::download, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("download")) { Text("Save for offline") }
    } ?: Text(if (state.busy) "Loading the library…" else "The library is unavailable. Refresh when the local service is connected.")
    Text("An eight-second pause in colour and motion. Watch the original study now, or keep a verified copy for a moment without a connection.", style = MaterialTheme.typography.bodyLarge)
}
@Composable fun FilmPlayer(uri:String){
    val context=LocalContext.current
    val player=remember(uri){ExoPlayer.Builder(context).build()}
    var status by remember(uri){mutableStateOf("Preparing media")}
    DisposableEffect(player){
        val listener=object:Player.Listener{
            override fun onPlaybackStateChanged(state:Int){status=when(state){Player.STATE_READY->"Ready to play";Player.STATE_ENDED->"Playback finished";Player.STATE_BUFFERING->"Buffering";else->"Player idle"}}
            override fun onPlayerError(error:PlaybackException){status="Playback unavailable; close and retry when the source is reachable."}
        }
        player.addListener(listener);player.setMediaItem(MediaItem.fromUri(uri));player.prepare();player.playWhenReady=true
        onDispose{player.removeListener(listener);player.release()}
    }
    AndroidView(factory={PlayerView(it).apply{this.player=player}},update={it.player=player},modifier=Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("player"))
    Text(status,modifier=Modifier.testTag("player-status"))
}
