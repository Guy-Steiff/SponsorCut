package com.sponsorcut

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest

data class SponsorSegmentInfo(
    val category: String,
    val start: Double,
    val end: Double
)

class SponsorBlockClient {

    private val client = OkHttpClient()
    private val tag = "SponsorBlockClient"

    fun fetchRich(videoId: String): List<SponsorSegmentInfo> {

        if (videoId.length != 11) {
            Log.w(tag, "Skipping SponsorBlock fetch: invalid videoId='$videoId'")
            return emptyList()
        }

        val hashPrefix = sha256Hex(videoId).take(4)
        val categories = "[\"sponsor\"]"

        val url = "https://sponsor.ajay.app/api/skipSegments/$hashPrefix"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("categories", categories)
            ?.build()
            ?: return emptyList()

        Log.i(tag, "Fetching segments for videoId=$videoId hashPrefix=$hashPrefix")

        val req = Request.Builder().url(url).build()

        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(tag, "SponsorBlock response=${res.code} for videoId=$videoId")
                return emptyList()
            }

            val body = res.body?.string() ?: return emptyList()
            val root = JSONArray(body)
            val parsed = ArrayList<SponsorSegmentInfo>()

            for (i in 0 until root.length()) {
                val obj = root.optJSONObject(i) ?: continue
                val entryVideoId = obj.optString("videoID")
                if (entryVideoId != videoId) continue

                val segments = obj.optJSONArray("segments") ?: continue
                for (j in 0 until segments.length()) {
                    val segObj = segments.optJSONObject(j) ?: continue
                    if (segObj.optString("actionType", "skip") != "skip") continue
                    val seg = segObj.optJSONArray("segment") ?: continue
                    if (seg.length() < 2) continue
                    val start = seg.optDouble(0, Double.NaN)
                    val end = seg.optDouble(1, Double.NaN)
                    if (!start.isNaN() && !end.isNaN() && end > start) {
                        parsed += SponsorSegmentInfo(
                            category = segObj.optString("category", "sponsor"),
                            start = start,
                            end = end
                        )
                    }
                }
            }

            Log.i(tag, "SponsorBlock videoId=$videoId segments=${parsed.size} $parsed")
            return parsed
        }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
