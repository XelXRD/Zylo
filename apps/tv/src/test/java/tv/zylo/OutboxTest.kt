package tv.zylo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
  @Test fun keepsWhatCanBeRetriedAndLogsEverything() {
    val files = HashMap<String, Mem>()
    val store = Store { files.getOrPut(it) { Mem() } }
    // Not signed in to AniList yet: kept (it'll go once you connect), with the reason logged.
    store.put("outbox", "[" + JSONObject().put("service", "anilist").put("id", 1).put("episode", 3).put("label", "Frieren S1E3") + "," +
      // A broken job (no id) can never succeed: dropped, but still logged.
      JSONObject().put("service", "mal").put("episode", 3).put("label", "Broken") + "]")
    Outbox.flush(store)
    assertEquals(1, Outbox.pending(store))
    val log = Outbox.entries(store)
    assertEquals(2, log.size)
    assertTrue(log.all { !it.ok })
    assertTrue(log.any { it.label == "Frieren S1E3" && "isn't connected" in it.result && "Will retry" in it.result })
    assertFalse(log.first { it.label == "Broken" }.result.contains("Will retry"))
  }
}
