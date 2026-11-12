package com.isaakrichards.aisletunes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Station {
    const val NAME = "Walmart & Sam's Club Radio"
    const val STREAM = "https://streams.radiomast.io/2ce0b08d-2fe6-42a1-b64a-9f0a682f5508"
    const val METADATA = "$STREAM/metadata"
}

data class Track(
    val raw: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val year: String = "",
    val artUrl: String? = null,
    val thumbUrl: String? = null,
    val airedAt: Long = System.currentTimeMillis(),
)

/** Shared now-playing state, written by the service and read by the screen. */
object NowPlaying {
    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current

    private val _history = MutableStateFlow<List<Track>>(emptyList())
    val history: StateFlow<List<Track>> = _history

    /** Returns true when [raw] is a new song. */
    fun announce(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.isEmpty() || clean == _current.value?.raw) return false
        _current.value?.let { prev -> _history.value = (listOf(prev) + _history.value).take(6) }
        val i = clean.indexOf(" - ")
        _current.value = if (i > 0) {
            Track(clean, clean.substring(i + 3).trim(), clean.substring(0, i).trim())
        } else {
            Track(clean, clean, "")
        }
        return true
    }

    fun enrich(raw: String, found: Track) {
        val now = _current.value ?: return
        if (now.raw != raw) return
        _current.value = now.copy(
            title = now.title.ifBlank { found.title },
            artist = now.artist.ifBlank { found.artist },
            album = found.album,
            year = found.year,
            artUrl = found.artUrl,
            thumbUrl = found.thumbUrl,
        )
    }
}

/** Reads the station's live song announcements (server-sent events). */
object MetadataStream {
    fun songs(): Flow<String> = flow {
        while (true) {
            try {
                val c = URL(Station.METADATA).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "text/event-stream")
                c.connectTimeout = 15_000
                c.readTimeout = 180_000
                c.inputStream.bufferedReader().use { reader ->
                    val data = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val song = parse(data.toString())
                                if (song.isNotBlank()) emit(song)
                                data.setLength(0)
                            }
                        } else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }
                c.disconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(5_000)
        }
    }.flowOn(Dispatchers.IO)

    private fun parse(s: String): String = try {
        val o = JSONObject(s)
        o.optString("metadata").ifBlank { o.optString("title") }
    } catch (_: Exception) {
        s
    }
}

/** Looks up album, year and cover art in Apple's public music catalog. */
object ArtLookup {
    private val cache = HashMap<String, Track?>()

    suspend fun find(raw: String): Track? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(raw)) return@withContext cache[raw] }
        val result = try {
            val term = raw.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(" - ", " ")
            val url = "https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" +
                URLEncoder.encode(term, "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results")
            if (results == null || results.length() == 0) null else {
                val t = results.getJSONObject(0)
                val thumb = t.optString("artworkUrl100")
                Track(
                    raw = raw,
                    title = t.optString("trackName"),
                    artist = t.optString("artistName"),
                    album = t.optString("collectionName"),
                    year = t.optString("releaseDate").take(4),
                    artUrl = thumb.replace("100x100bb", "600x600bb").ifBlank { null },
                    thumbUrl = thumb.ifBlank { null },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        synchronized(cache) { cache[raw] = result }
        result
    }
}
