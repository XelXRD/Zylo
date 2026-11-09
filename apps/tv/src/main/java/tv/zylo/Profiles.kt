// Profiles: each person gets their own Continue Watching, My List, Trakt / anime sign-ins, remembered sources and
// subtitle language. Keys, addons, media servers and app settings are shared by everyone on the TV.
package tv.zylo

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay

/** A round avatar: the profile's initial on its colour. */
@Composable
fun Avatar(p: Profile, size: Dp) = Box(Modifier.size(size).background(Color(p.color), CircleShape), contentAlignment = Alignment.Center) {
  Text(p.name.take(1).uppercase(), style = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp), color = Color.White)
}

/** Switch to a profile: the app rebuilds itself as that person (their rows, lists and sign-ins). A PIN is asked first. */
fun switchTo(activity: Activity?, store: Store, id: String) {
  if (id == store.profileId) return
  Pins.check(activity, store, listOf(id), "PIN for ${store.profiles.find { it.id == id }?.name}") {
    store.switchProfile(id)
    activity?.recreate()
  }
}

/**
 * 4-digit PINs. A profile with one asks for it when you switch to it (and when Zylo opens on it). Settings on a kids
 * profile asks for any grown-up profile's PIN, so the rating limit can't be turned off. Kept in app storage on the TV.
 */
object Pins {
  /** Opened with the right PIN since Zylo started (reset when the app is closed). */
  var unlocked = false
  fun of(store: Store, id: String) = store.str("pin_$id")
  fun set(store: Store, id: String, pin: String) = store.put("pin_$id", pin)
  fun valid(pin: String) = pin.length == 4 && pin.all(Char::isDigit)
  /** The PINs that open Settings on this profile: none unless it's a kids profile and a grown-up has a PIN. */
  fun forSettings(store: Store) = if (store.profile.kids == null) emptyList() else store.profiles.filter { it.kids == null }.map { of(store, it.id) }.filter { it.isNotEmpty() }
  /** Runs [then] at once if none of [ids] have a PIN; otherwise after one of their PINs is typed. */
  fun check(ctx: android.content.Context?, store: Store, ids: List<String>, title: String, then: () -> Unit) {
    val pins = ids.map { of(store, it) }.filter { it.isNotEmpty() }
    if (pins.isEmpty() || ctx == null) return then()
    ask(ctx, title, "", pin = true) { typed -> if (typed in pins) { unlocked = true; then() } else toast(ctx, "Wrong PIN") }
  }
}

@Composable
fun ProfilesPage(store: Store, nav: Nav) {
  val ctx = LocalContext.current
  var profiles by remember { mutableStateOf(store.profiles) }
  val current = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { current.requestFocus() } }
  // Back can't skip the PIN when Zylo opens on a profile that has one.
  androidx.activity.compose.BackHandler(!Pins.unlocked && Pins.of(store, store.profileId).isNotEmpty()) {}
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Logo(30.dp)
    Text("Who's watching?", Modifier.padding(top = 28.dp, bottom = 36.dp), style = MaterialTheme.typography.displaySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
      profiles.forEach { p ->
        ProfileTile(p.name + (p.kids?.let { "  ·  Kids" } ?: ""), if (p.id == store.profileId) Modifier.focusRequester(current) else Modifier, onClick = {
          // Opening on a profile with a PIN: it's asked here too, even to stay on it.
          if (p.id != store.profileId) switchTo(ctx as? Activity, store, p.id)
          else if (Pins.unlocked) nav.back()
          else Pins.check(ctx, store, listOf(p.id), "PIN for ${p.name}") { Pins.unlocked = true; nav.back() }
        }) { Avatar(p, 96.dp) }
      }
      if (profiles.size < 6 && store.profile.kids == null) ProfileTile("Add Profile", Modifier, onClick = {
        ask(ctx, "Name for the new profile", "") { name -> if (name.isNotBlank()) { store.addProfile(name); profiles = store.profiles } }
      }) {
        Box(Modifier.size(96.dp).background(Z.Card, CircleShape), contentAlignment = Alignment.Center) { Glyph(Icons.Filled.Add, 36.dp, Z.Text2) }
      }
    }
    Text("Rename or remove profiles in Settings > Profiles.", Modifier.padding(top = 40.dp), style = MaterialTheme.typography.bodySmall, color = Z.Muted)
  }
}

@Composable
private fun ProfileTile(label: String, modifier: Modifier, onClick: () -> Unit, picture: @Composable () -> Unit) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Surface(
    onClick, modifier,
    shape = ClickableSurfaceDefaults.shape(CircleShape),
    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = 4.dp, shape = CircleShape)),
    scale = ClickableSurfaceDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else 1.1f, pressedScale = Z.Press),
  ) { Box(Modifier.padding(8.dp)) { picture() } }
  Text(label, Modifier.padding(top = 10.dp).width(120.dp), style = MaterialTheme.typography.titleSmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Settings > Profiles: rename and remove. */
@Composable
fun ProfilesSettings(store: Store) {
  val ctx = LocalContext.current
  var profiles by remember { mutableStateOf(store.profiles) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Profiles", style = MaterialTheme.typography.displaySmall)
    Text("Each profile keeps its own Continue Watching, My List, Trakt and anime sign-ins, remembered sources and subtitle language. Keys, addons and media servers are shared. A kids profile only shows titles rated at or below its level; unrated titles are hidden. Give grown-up profiles a PIN so kids can't switch to them or open Settings.",
      Modifier.padding(bottom = 8.dp).fillMaxWidth(0.85f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
    profiles.forEach { p ->
      Row(Modifier.fillMaxWidth().background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, 44.dp)
        Spacer(Modifier.width(16.dp))
        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (p.id == store.profileId) StatusChip("Watching now", Z.Success)
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ZButton("Rename", { ask(ctx, "Profile name", p.name) { store.renameProfile(p.id, it); profiles = store.profiles } })
          // Off → U → PG → 12 → 15 → Off. The profile in use takes it at once (its rows reload).
          ZButton("Kids: " + (p.kids?.let { Ratings.LEVELS[it] + " and under" } ?: "Off"), {
            val next = when (val k = p.kids) { null -> 0; Ratings.LEVELS.lastIndex -> null; else -> k + 1 }
            store.setKids(p.id, next)
            profiles = store.profiles
            if (p.id == store.profileId) (ctx as? Activity)?.recreate()
          })
          var pin by remember(p.id) { mutableStateOf(Pins.of(store, p.id).isNotEmpty()) }
          ZButton(if (pin) "PIN: On" else "PIN: Off", {
            ask(ctx, if (pin) "New 4-digit PIN for ${p.name} (leave empty to remove)" else "4-digit PIN for ${p.name}", "", pin = true) {
              when {
                it.isEmpty() -> { Pins.set(store, p.id, ""); pin = false }
                Pins.valid(it) -> { Pins.set(store, p.id, it); pin = true; toast(ctx, "PIN set for ${p.name}") }
                else -> toast(ctx, "A PIN is 4 digits")
              }
            }
          })
          if (p.id != store.profileId) ZButton("Switch", { switchTo(ctx as? Activity, store, p.id) })
          if (p.id != "main" && p.id != store.profileId) ZButton("Remove", { store.deleteProfile(p.id); profiles = store.profiles; toast(ctx, "Removed ${p.name}") })
        }
      }
    }
    ZButton("Add Profile", { ask(ctx, "Name for the new profile", "") { if (it.isNotBlank()) { store.addProfile(it); profiles = store.profiles } } }, primary = true, icon = Icons.Filled.Add)
  }
}
