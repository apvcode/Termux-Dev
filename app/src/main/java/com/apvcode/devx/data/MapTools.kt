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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import android.os.CancellationSignal

/**
 * Where the person is, and the places and routes of 2GIS (the map of the CIS countries; Google Maps comes through its MCP
 * server, see the MCP presets). The person's position is read only when asked for, with their permission.
 */
class MapTools(private val context: Context) {
    val names = setOf("get_location", "find_places", "get_route", "get_weather", "twogis_search", "twogis_route")

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** The 2GIS tools are offered only when they are on and have their key. */
    fun twoGisKey(): String? = Secrets(context).get(KEY_ID)?.trim()?.takeIf { it.isNotEmpty() }

    fun definitions(): List<WireTool> = buildList {
        add(
            tool(
                "find_places",
                "Find places, businesses and addresses on OpenStreetMap (worldwide, no key): \"pharmacy\", \"Lenina 5, Kazan\". With a centre's coordinates (from get_location) it gives what is near, nearest first: names, addresses, hours, phone and coordinates (for get_route).",
                schema(
                    "query" to str("What or where to look for."),
                    "lat" to number("Latitude of the centre (optional)."),
                    "lon" to number("Longitude of the centre (optional)."),
                    "radius_m" to int("Radius around the centre in metres (default 2000, at most 30000)."),
                    "limit" to int("How many results (default 6, at most 10)."),
                    required = listOf("query"),
                ),
            ),
        )
        add(
            tool(
                "get_route",
                "Time and distance of a trip on OpenStreetMap roads: by car, on foot or by bicycle (no public transport). Needs the coordinates of both ends.",
                schema(
                    "from_lat" to number("Start latitude."), "from_lon" to number("Start longitude."),
                    "to_lat" to number("End latitude."), "to_lon" to number("End longitude."),
                    "mode" to str("driving (default), walking or bicycle."),
                    required = listOf("from_lat", "from_lon", "to_lat", "to_lon"),
                ),
            ),
        )
        add(
            tool(
                "get_weather",
                "The weather at a place now and for the next three days (no key needed). Give coordinates (from get_location or find_places).",
                schema("lat" to number("Latitude."), "lon" to number("Longitude."), required = listOf("lat", "lon")),
            ),
        )
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
            "find_places" -> findPlaces(args, ::act)
            "get_route" -> osmRoute(args, ::act)
            "get_weather" -> weather(args, ::act)
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
        val place = withContext(Dispatchers.IO) { runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.logged("maptools").getOrNull() }
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
                            val cancel = CancellationSignal()
                            cont.invokeOnCancellation { cancel.cancel() }
                            runCatching { lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) } }.logged("maptools").onFailure { if (cont.isActive) cont.resume(null) }
                        }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    // ---------- the free map (OpenStreetMap, Open-Meteo): no keys ----------

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    private suspend fun getJson(url: okhttp3.HttpUrl): Any? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Devx-Android/1.0 (personal assistant app)").build()
            http.newCall(request).execute().use { r -> r.body?.string().orEmpty().let { if (it.trimStart().startsWith("[")) JSONArray(it) else JSONObject(it) } }
        }.logged("maptools").getOrNull()
    }

    private suspend fun findPlaces(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolOutcome("Say what to look for.")
        act("OpenStreetMap: $query")
        val limit = args.optInt("limit", 6).coerceIn(1, 10)
        val builder = okhttp3.HttpUrl.Builder().scheme("https").host("nominatim.openstreetmap.org").addPathSegment("search")
            .addQueryParameter("q", query).addQueryParameter("format", "jsonv2").addQueryParameter("addressdetails", "1").addQueryParameter("extratags", "1")
            .addQueryParameter("limit", (limit * 2).coerceAtMost(20).toString()).addQueryParameter("accept-language", Locale.getDefault().language)
        val hasCentre = args.has("lat") && args.has("lon")
        val lat = args.optDouble("lat"); val lon = args.optDouble("lon")
        if (hasCentre) {
            val radius = args.optInt("radius_m", 2000).coerceIn(200, 30_000).toDouble()
            val dLat = radius / 111_320.0
            val dLon = radius / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
            builder.addQueryParameter("viewbox", "${lon - dLon},${lat + dLat},${lon + dLon},${lat - dLat}").addQueryParameter("bounded", "1")
        }
        val array = getJson(builder.build()) as? JSONArray ?: return ToolOutcome("The free map did not answer. Check the connection.")
        if (array.length() == 0) return ToolOutcome("Nothing found for \"$query\"" + (if (hasCentre) " near that point. Try a wider radius or other words." else ".") )
        class Hit(val text: String, val metres: Double?)
        val hits = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val plat = o.optString("lat").toDoubleOrNull() ?: 0.0
            val plon = o.optString("lon").toDoubleOrNull() ?: 0.0
            val extra = o.optJSONObject("extratags")
            val name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") }
            val where = o.optString("display_name").substringAfter(",", "").trim().take(110)
            val kind = o.optString("type").replace('_', ' ')
            val hours = extra?.optString("opening_hours").orEmpty()
            val phone = (extra?.optString("phone").orEmpty()).ifBlank { extra?.optString("contact:phone").orEmpty() }
            val metres = if (hasCentre) dist(lat, lon, plat, plon) else null
            Hit(
                "$name — $where [$kind]" + (if (hours.isNotBlank()) ", hours: $hours" else "") + (if (phone.isNotBlank()) ", phone: $phone" else "") +
                    (if (metres != null) ", ${if (metres < 1000) "${metres.toInt()} m" else "%.1f km".format(Locale.US, metres / 1000)}" else "") +
                    " (lat ${"%.5f".format(Locale.US, plat)}, lon ${"%.5f".format(Locale.US, plon)})",
                metres,
            )
        }.let { list -> if (hasCentre) list.sortedBy { it.metres } else list }.take(limit)
        return ToolOutcome("OpenStreetMap results:\n" + hits.mapIndexed { i, h -> "${i + 1}. ${h.text}" }.joinToString("\n"))
    }

    private suspend fun osmRoute(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val (host, label) = when (args.optString("mode", "driving").lowercase()) {
            "walking", "walk", "foot", "pedestrian" -> "routed-foot" to "on foot"
            "bicycle", "bike", "cycling" -> "routed-bike" to "by bicycle"
            else -> "routed-car" to "by car"
        }
        act("OpenStreetMap: $label")
        val a = "${args.optDouble("from_lon")},${args.optDouble("from_lat")}"
        val b = "${args.optDouble("to_lon")},${args.optDouble("to_lat")}"
        val url = ("https://routing.openstreetmap.de/$host/route/v1/driving/$a;$b?overview=false").toHttpUrlOrNull() ?: return ToolOutcome("Those coordinates are not valid.")
        val body = getJson(url) as? JSONObject ?: return ToolOutcome("The route service did not answer. Check the connection.")
        val route = body.optJSONArray("routes")?.optJSONObject(0) ?: return ToolOutcome("No route found (${body.optString("message").ifBlank { body.optString("code") }}).")
        val minutes = Math.round(route.optDouble("duration") / 60.0)
        val km = route.optDouble("distance") / 1000.0
        return ToolOutcome("$label: about ${if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"}, ${"%.1f".format(Locale.US, km)} km (OpenStreetMap, without live traffic).")
    }

    private suspend fun weather(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        act("Open-Meteo")
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.open-meteo.com").addPathSegments("v1/forecast")
            .addQueryParameter("latitude", args.optDouble("lat").toString()).addQueryParameter("longitude", args.optDouble("lon").toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code")
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "3").build()
        val body = getJson(url) as? JSONObject ?: return ToolOutcome("The weather service did not answer. Check the connection.")
        val cur = body.optJSONObject("current") ?: return ToolOutcome("No weather for that place.")
        val daily = body.optJSONObject("daily")
        val lines = ArrayList<String>()
        lines += "Now: ${cur.optDouble("temperature_2m").toInt()}°C (feels like ${cur.optDouble("apparent_temperature").toInt()}°C), ${weatherWord(cur.optInt("weather_code"))}, wind ${cur.optDouble("wind_speed_10m").toInt()} km/h, precipitation ${cur.optDouble("precipitation")} mm."
        val days = daily?.optJSONArray("time")
        if (days != null) for (i in 0 until days.length()) {
            lines += "${days.getString(i)}: ${daily.optJSONArray("temperature_2m_min")?.optDouble(i)?.toInt()}…${daily.optJSONArray("temperature_2m_max")?.optDouble(i)?.toInt()}°C, ${weatherWord(daily.optJSONArray("weather_code")?.optInt(i) ?: -1)}, rain chance ${daily.optJSONArray("precipitation_probability_max")?.optInt(i)}%"
        }
        return ToolOutcome(lines.joinToString("\n") + "\n(Open-Meteo)")
    }

    private fun weatherWord(code: Int) = when (code) {
        0 -> "clear"; 1, 2 -> "mostly clear"; 3 -> "overcast"; 45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"; 61, 63, 65, 66, 67 -> "rain"; 71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "showers"; 85, 86 -> "snow showers"; 95, 96, 99 -> "thunderstorm"; else -> "unknown"
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
            }.logged("maptools").getOrNull()
        } ?: return ToolOutcome("2GIS did not answer. Check the key and the connection.")
        val result = body.optJSONArray("result")?.optJSONObject(0)
            ?: return ToolOutcome("2GIS found no route (" + (body.optString("message").ifBlank { body.optJSONObject("error")?.optString("message").orEmpty() }.ifBlank { "unknown reason" }) + ").")
        val minutes = (result.optInt("total_duration") / 60.0).let { Math.round(it) }
        val km = result.optInt("total_distance") / 1000.0
        return ToolOutcome("By $mode: about $minutes min, ${"%.1f".format(Locale.US, km)} km (2GIS).")
    }

    private suspend fun get(url: okhttp3.HttpUrl): JSONObject? = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body?.string().orEmpty()) } }.logged("maptools").getOrNull()
    }

    companion object {
        const val KEY_ID = "twogis_key"

        /** Countries where 2GIS is the map to use. */
        private val TWO_GIS_COUNTRIES = setOf("RU", "KZ", "UZ", "KG", "BY", "AZ", "AM", "GE", "MD", "TJ", "TM")

        fun promptSection(twoGisOn: Boolean): String = """
## Places, routes and weather
get_location tells where the person is and which map service suits the country. Without any key you have the free OpenStreetMap tools: find_places (places and addresses, and coordinates for a name), get_route (car, foot, bicycle) and get_weather. Use them by default. When the person needs more detail, such as ratings, busy hours or public transport in the CIS countries, use 2GIS${if (twoGisOn) " (twogis_search, twogis_route), which has its key now" else " (it works only after the person adds a free key in Devx settings, Assistant, 2GIS: say so once if they need that detail)"}; abroad the Google Maps tools (named mcp__…google_maps…) are the detailed ones when that MCP server is added. If a service does not answer, use another, or show the place with show_on_map. For \"near me\" call get_location first, then find_places with its coordinates; for \"how long to X\" find X's coordinates with find_places, then get_route. For \"home\" or \"work\" use what you remember about the person (memory) or ask once. Say which service the answer came from in a few words.
""".trim()
    }
}

private fun number(description: String): JSONObject = JSONObject().put("type", "number").put("description", description)
