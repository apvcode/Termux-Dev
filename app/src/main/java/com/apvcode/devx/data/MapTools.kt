package com.apvcode.devx.data

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Where the person is, and the places and routes of 2GIS (the map of the CIS countries; Google Maps comes through its MCP
 * server, see the MCP presets). The person's position is read only when asked for, with their permission.
 */
class MapTools(private val context: Context) {
    val names = setOf("get_location", "twogis_search", "twogis_route")

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** The 2GIS tools are offered only when they are on and have their key. */
    fun twoGisKey(): String? = Secrets(context).get(KEY_ID)?.trim()?.takeIf { it.isNotEmpty() }

    fun definitions(): List<WireTool> = buildList {
        add(tool("get_location", "Where the person is now: coordinates, the address, the country, and which map service suits that country (2GIS for the CIS countries, Google Maps elsewhere). Needs the location permission (the phone asks once). Use it for \"what is near me\", \"how far to ...\", \"weather here\".", schema()))
        if (Prefs.twoGis && twoGisKey() != null) {
            add(
                tool(
                    "twogis_search",
                    "Search places and organizations on the 2GIS map (shops, cafes, pharmacies, addresses...). Give the coordinates of the centre (from get_location) to get what is near it. Gives names, addresses, categories, ratings and coordinates.",
                    schema(
                        "query" to str("What to look for, e.g. \"pharmacy\" or \"coffee\"."),
                        "lat" to number("Latitude of the centre (optional)."),
                        "lon" to number("Longitude of the centre (optional)."),
                        "radius_m" to int("Radius in metres around the centre (default 1500, at most 40000)."),
                        "limit" to int("How many results (default 6, at most 10)."),
                        required = listOf("query"),
                    ),
                ),
            )
            add(
                tool(
                    "twogis_route",
                    "How long a trip takes and how far it is, on the 2GIS map, by car, on foot, by bicycle or by public transport. Needs coordinates of both ends (from get_location and twogis_search).",
                    schema(
                        "from_lat" to number("Start latitude."), "from_lon" to number("Start longitude."),
                        "to_lat" to number("End latitude."), "to_lon" to number("End longitude."),
                        "mode" to str("driving (default), walking, bicycle or public_transport."),
                        required = listOf("from_lat", "from_lon", "to_lat", "to_lon"),
                    ),
                ),
            )
        }
    }

    suspend fun run(name: String, args: JSONObject, report: ToolReport): ToolOutcome {
        fun act(detail: String) = report.activity(ToolActivity(ToolKind.Phone, detail))
        return when (name) {
            "get_location" -> location(::act)
            "twogis_search" -> search(args, ::act)
            "twogis_route" -> route(args, ::act)
            else -> ToolOutcome("Unknown tool: $name")
        }
    }

    // ---------- where the person is ----------

    private suspend fun location(act: (String) -> Unit): ToolOutcome {
        act("")
        val granted = PermissionBridge.ensure(context, Manifest.permission.ACCESS_FINE_LOCATION) || PermissionBridge.has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!granted) return ToolOutcome("The location permission is not given. Ask the person to allow it (Devx must be on the screen for the phone to ask), or to say where they are.")
        val loc = currentLocation() ?: return ToolOutcome("The phone could not tell where it is (location may be switched off, or there is no signal). Ask the person to turn location on, or to say where they are.")
        val place = withContext(Dispatchers.IO) { runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull() }
        val country = place?.countryCode.orEmpty()
        val service = if (country in TWO_GIS_COUNTRIES) "2GIS (twogis_search, twogis_route)" else "Google Maps (its MCP tools)"
        val address = place?.let { listOfNotNull(it.thoroughfare, it.subThoroughfare, it.locality, it.countryName).joinToString(", ") }.orEmpty()
        return ToolOutcome(
            "lat ${"%.5f".format(Locale.US, loc.latitude)}, lon ${"%.5f".format(Locale.US, loc.longitude)}, accuracy ${loc.accuracy.toInt()} m" +
                (if (address.isNotBlank()) "\naddress: $address" else "") +
                (if (country.isNotBlank()) "\ncountry: $country" else "") +
                "\nmap service for this country: $service",
        )
    }

    @Suppress("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30) {
            for (provider in providers) {
                val fresh = withContext(Dispatchers.Main) {
                    kotlinx.coroutines.withTimeoutOrNull(9_000) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            val cancel = android.os.CancellationSignal()
                            cont.invokeOnCancellation { cancel.cancel() }
                            runCatching { lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) } }.onFailure { if (cont.isActive) cont.resume(null) }
                        }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    // ---------- 2GIS ----------

    private suspend fun search(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val key = twoGisKey() ?: return ToolOutcome("2GIS has no key yet. Tell the person to add it in Devx settings, Assistant, 2GIS.")
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolOutcome("Say what to look for.")
        act("2GIS: $query")
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("catalog.api.2gis.com").addPathSegments("3.0/items")
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "items.point,items.address_name,items.rubrics,items.reviews,items.schedule")
            .addQueryParameter("page_size", args.optInt("limit", 6).coerceIn(1, 10).toString())
            .addQueryParameter("locale", Locale.getDefault().language.let { if (it == "ru") "ru_RU" else "en_US" })
            .addQueryParameter("key", key)
        if (args.has("lat") && args.has("lon")) {
            url.addQueryParameter("location", "${args.optDouble("lon")},${args.optDouble("lat")}")
            url.addQueryParameter("radius", args.optInt("radius_m", 1500).coerceIn(100, 40_000).toString())
            url.addQueryParameter("sort", "distance")
        }
        val body = get(url.build()) ?: return ToolOutcome("2GIS did not answer. Check the key and the connection.")
        val meta = body.optJSONObject("meta")
        if ((meta?.optInt("code", 200) ?: 200) != 200) return ToolOutcome("2GIS said: " + (meta?.optJSONObject("error")?.optString("message") ?: "error ${meta?.optInt("code")}") + ". If the key is wrong, tell the person to check it in settings.")
        val items = body.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return ToolOutcome("Nothing found on 2GIS for \"$query\".")
        val lines = (0 until items.length()).map { i ->
            val it = items.getJSONObject(i)
            val point = it.optJSONObject("point")
            val rubrics = it.optJSONArray("rubrics")?.let { r -> (0 until r.length()).joinToString("/") { j -> r.getJSONObject(j).optString("name") } }.orEmpty()
            val rating = it.optJSONObject("reviews")?.optString("general_rating")?.takeIf { r -> r.isNotBlank() && r != "null" }
            "${i + 1}. ${it.optString("name")} — ${it.optString("address_name")}" + (if (rubrics.isNotBlank()) " [$rubrics]" else "") + (if (rating != null) " rating $rating" else "") +
                (if (point != null) " (lat ${point.optDouble("lat")}, lon ${point.optDouble("lon")})" else "")
        }
        return ToolOutcome("2GIS results:\n" + lines.joinToString("\n"))
    }

    private suspend fun route(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val key = twoGisKey() ?: return ToolOutcome("2GIS has no key yet. Tell the person to add it in Devx settings, Assistant, 2GIS.")
        val mode = when (args.optString("mode", "driving").lowercase()) {
            "walking", "walk", "pedestrian" -> "walking"
            "bicycle", "bike", "cycling" -> "bicycle"
            "public_transport", "transit", "bus" -> "public_transport"
            else -> "driving"
        }
        act("2GIS: $mode")
        val points = JSONArray()
            .put(JSONObject().put("type", "walking").put("x", args.optDouble("from_lon")).put("y", args.optDouble("from_lat")))
            .put(JSONObject().put("type", "walking").put("x", args.optDouble("to_lon")).put("y", args.optDouble("to_lat")))
        val payload = JSONObject().put("points", points).put("transport", mode).put("output", "summary").put("locale", Locale.getDefault().language)
        val body = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://routing.api.2gis.com/routing/7.0.0/global?key=$key").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { JSONObject(it.body?.string().orEmpty()) }
            }.getOrNull()
        } ?: return ToolOutcome("2GIS did not answer. Check the key and the connection.")
        val result = body.optJSONArray("result")?.optJSONObject(0)
            ?: return ToolOutcome("2GIS found no route (" + (body.optString("message").ifBlank { body.optJSONObject("error")?.optString("message").orEmpty() }.ifBlank { "unknown reason" }) + ").")
        val minutes = (result.optInt("total_duration") / 60.0).let { Math.round(it) }
        val km = result.optInt("total_distance") / 1000.0
        return ToolOutcome("By $mode: about $minutes min, ${"%.1f".format(Locale.US, km)} km (2GIS).")
    }

    private suspend fun get(url: okhttp3.HttpUrl): JSONObject? = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body?.string().orEmpty()) } }.getOrNull()
    }

    companion object {
        const val KEY_ID = "twogis_key"

        /** Countries where 2GIS is the map to use. */
        private val TWO_GIS_COUNTRIES = setOf("RU", "KZ", "UZ", "KG", "BY", "AZ", "AM", "GE", "MD", "TJ", "TM")

        fun promptSection(twoGisOn: Boolean): String = """
## Places, routes and weather
get_location tells where the person is and which map service suits the country. Use 2GIS${if (twoGisOn) " (twogis_search, twogis_route)" else " (not available now: it needs its key in settings)"} for places, distances and routes in Russia, Kazakhstan, Belarus, Uzbekistan, Kyrgyzstan and the other CIS countries; use the Google Maps tools (the ones named mcp__…google_maps…, when that MCP server is added) anywhere else, for travel and abroad, and for weather by place (lookup_weather). If the preferred service is not available, use the other one, or open_link / show_on_map to show the place on the phone's map app. For \"near me\" call get_location first. For \"home\" or \"work\" use what you remember about the person (memory) or ask once. Say which service the answer came from in a few words.
""".trim()
    }
}

private fun number(description: String): JSONObject = JSONObject().put("type", "number").put("description", description)
