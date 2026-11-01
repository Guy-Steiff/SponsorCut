// app/src/main/java/com/sponsorcut/MegaCutClient.kt

package com.sponsorcut

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class MediaSource(
    val platform: String,
    val externalId: String,
    val canonicalUrl: String?,
    val identifierKind: String,
    val oEmbedExternalId: String? = null
)

data class MegaCutSegmentSet(
    val id: String,
    val title: String?,
    val show: String?,
    val platform: String,
    val sourceUrl: String?,
    val variant: String?,
    val externalId: String?,
    val channelName: String?,
    val segments: List<SponsorSegmentInfo>
)

/**
 * Client for the MegaCut Web segment-set database.
 *
 * The LAN base URL is intentionally hardcoded for current development.
 * It must not become a user-editable UI field or a frontend-visible setting.
 */
class MegaCutClient(
    private val baseUrl: String = "http://192.168.212.86:8787/api"
) {

    private val client = OkHttpClient()
    private val tag = "MegaCutClient"

    fun parseMediaSource(rawInput: String): MediaSource? {
        val input = rawInput.trim()
        if (input.isBlank()) return null

        val decoded = runCatching { java.net.URLDecoder.decode(input, "UTF-8") }
            .getOrDefault(input)

        // YouTube URL forms.
        val youtubePatterns = listOf(
            Regex("[?&]v=([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/live/([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/embed/([a-zA-Z0-9_-]{11})")
        )
        for (pattern in youtubePatterns) {
            pattern.find(decoded)?.let { match ->
                val id = match.groupValues[1]
                return MediaSource(
                    platform = "youtube",
                    externalId = id,
                    canonicalUrl = "https://www.youtube.com/watch?v=$id",
                    identifierKind = "youtube"
                )
            }
        }

        // Bare YouTube ID.
        if (decoded.length == 11 &&
            decoded.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) {
            return MediaSource(
                platform = "youtube",
                externalId = decoded,
                canonicalUrl = "https://www.youtube.com/watch?v=$decoded",
                identifierKind = "youtube"
            )
        }

        // Spotify full URL.
        val spotifyUrl = Regex(
            "^(?:https?://)?open\\.spotify\\.com/episode/([A-Za-z0-9]+)(?:[?#/].*)?$",
            RegexOption.IGNORE_CASE
        )
        spotifyUrl.find(decoded)?.let { match ->
            val id = match.groupValues[1]
            return MediaSource(
                platform = "spotify",
                externalId = id,
                canonicalUrl = "https://open.spotify.com/episode/$id",
                identifierKind = "episode"
            )
        }

        // Rumble page URL. Its v-ID is the page/source ID, not necessarily
        // the embed ID.
        val rumblePage = Regex(
            "^(?:https?://)?(?:www\\.)?rumble\\.com/(v[A-Za-z0-9]+)-([^/?#\\s]+?)(?:\\.html)?(?:[?#/].*)?$",
            RegexOption.IGNORE_CASE
        )
        rumblePage.find(decoded)?.let { match ->
            val pageId = match.groupValues[1]
            val slug = match.groupValues[2]
            return MediaSource(
                platform = "rumble",
                externalId = pageId,
                canonicalUrl = "https://rumble.com/$pageId-$slug.html",
                identifierKind = "rumble-page"
            )
        }

        // Rumble embed URL. Its v-ID is specifically an embed identifier.
        val rumbleEmbed = Regex(
            "^(?:https?://)?(?:www\\.)?rumble\\.com/embed/(v[A-Za-z0-9]+)/?(?:[?#/].*)?$",
            RegexOption.IGNORE_CASE
        )
        rumbleEmbed.find(decoded)?.let { match ->
            return MediaSource(
                platform = "rumble",
                externalId = match.groupValues[1],
                canonicalUrl = null,
                identifierKind = "rumble-embed"
            )
        }

        // No platform-specific URL pattern matched. Preserve the entire input
        // as a generic fallback candidate. MainActivity may try it as a Rumble
        // embed URL before giving up.
        return MediaSource(
            platform = "unknown",
            externalId = decoded,
            canonicalUrl = null,
            identifierKind = "generic-fallback"
        )
    }

    /**
     * Verifies a Rumble page URL or Spotify episode URL through the platform's
     * public oEmbed endpoint.
     *
     * A Rumble embed-only identifier cannot be verified directly here because
     * its canonical page URL is not inferable. The caller should first locate
     * a MegaCut summary, use its sourceUrl, and then call this method.
     */
    fun verifyOEmbed(source: MediaSource, canonicalUrl: String? = source.canonicalUrl): JSONObject? {
        if (source.platform != "rumble" && source.platform != "spotify") return null

        val url = canonicalUrl ?: return null
        val endpoint = when (source.platform) {
            "rumble" ->
                "https://rumble.com/api/Media/oembed.json?url=" +
                        URLEncoder.encode(url, "UTF-8")

            "spotify" ->
                "https://open.spotify.com/oembed?url=" +
                        URLEncoder.encode(url, "UTF-8")

            else -> return null
        }

        val request = Request.Builder().url(endpoint).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "oEmbed failed: platform=${source.platform} code=${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    /**
     * Finds an approved MegaCut segment-set summary for the supplied source.
     *
     * MegaCut currently exposes platform plus free-text q search. The external
     * identifier is used as q, then results are filtered locally according to
     * the kind of identifier supplied.
     */
    fun findSegmentSetSummary(source: MediaSource): JSONObject? {
        val url = baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("segment-sets")
            ?.apply {
                if (source.platform != "unknown") {
                    addQueryParameter("platform", source.platform)
                }
            }
            ?.addQueryParameter("q", source.externalId)
            ?.addQueryParameter("limit", "100")
            ?.addQueryParameter("offset", "0")
            ?.build()
            ?: return null

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "MegaCut search failed: code=${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val root = runCatching { JSONArray(body) }.getOrNull() ?: return null

            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                if (summaryMatchesSource(item, source)) return item
            }
        }

        return null
    }

    private fun summaryMatchesSource(item: JSONObject, source: MediaSource): Boolean {
        val itemExternalId = item.optString("externalId")
        val itemSourceUrl = item.optString("sourceUrl")
        val itemEmbedUrl = item.optString("embedUrl")

        return when (source.identifierKind) {
            "rumble-page" ->
                itemSourceUrl.contains(source.externalId) ||
                        itemExternalId == source.externalId ||
                        itemEmbedUrl.contains(source.externalId) ||
                        (source.oEmbedExternalId != null && (
                            itemExternalId == source.oEmbedExternalId ||
                                    itemEmbedUrl.contains(source.oEmbedExternalId)
                        ))

            "rumble-embed" ->
                itemExternalId == source.externalId ||
                        itemEmbedUrl.contains(source.externalId)

            "ambiguous-id" ->
                itemExternalId == source.externalId ||
                        itemSourceUrl.contains(source.externalId) ||
                        itemEmbedUrl.contains(source.externalId)

            "episode" ->
                itemExternalId == source.externalId ||
                        itemSourceUrl.contains(source.externalId)

            else ->
                itemExternalId == source.externalId ||
                        itemSourceUrl.contains(source.externalId)
        }
    }

    /**
     * Fetches the complete approved segment set, including its segments array.
     */
    fun fetchSegmentSetDetail(id: String): MegaCutSegmentSet? {
        val url = baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("segment-sets")
            ?.addPathSegment(id)
            ?.build()
            ?: return null

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "MegaCut detail failed: id=$id code=${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            return parseSegmentSet(root)
        }
    }

    private fun parseSegmentSet(root: JSONObject): MegaCutSegmentSet? {
        val id = root.optString("id")
        if (id.isBlank()) return null

        val segmentsJson = root.optJSONArray("segments") ?: return null
        val segments = mutableListOf<SponsorSegmentInfo>()

        for (i in 0 until segmentsJson.length()) {
            val segment = segmentsJson.optJSONObject(i) ?: continue

            // MegaCut currently stores segment timing in either the newer
            // { start, end, category } shape or the legacy SponsorBlock-style
            // { segment: [start, end], category } shape. Support both.
            val category = segment.optString("category")
                .ifBlank { "sponsor" }
                .lowercase()

            val start: Double
            val endValue: Any?

            val interval = segment.optJSONArray("segment")
            if (interval != null && interval.length() >= 2) {
                start = interval.optDouble(0, Double.NaN)
                endValue = interval.opt(1)
            } else {
                start = segment.optDouble("start", Double.NaN)
                endValue = segment.opt("end")
            }

            if (start.isNaN()) continue

            if (endValue == "end") {
                segments += SponsorSegmentInfo(
                    category = category,
                    start = start,
                    end = start,
                    endIsOpen = true
                )
                continue
            }

            val end = when (endValue) {
                is Number -> endValue.toDouble()
                is String -> endValue.toDoubleOrNull() ?: continue
                else -> continue
            }

            if (end > start) {
                segments += SponsorSegmentInfo(
                    category = category,
                    start = start,
                    end = end,
                    endIsOpen = false
                )
            }
        }

        return MegaCutSegmentSet(
            id = id,
            title = root.optString("title").ifBlank { null },
            show = root.optString("show").ifBlank { null },
            platform = root.optString("platform").ifBlank { "unknown" },
            sourceUrl = root.optString("sourceUrl").ifBlank { null },
            variant = root.optString("variant").ifBlank { null },
            externalId = root.optString("externalId").ifBlank { null },
            channelName = root.optString("channelName").ifBlank { null },
            segments = segments
        )
    }
}
