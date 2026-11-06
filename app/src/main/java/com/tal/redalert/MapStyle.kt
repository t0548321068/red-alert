package com.tal.redalert

import org.json.JSONArray
import org.json.JSONObject

/**
 * עיצוב מפת הרחובות (בסגנון גוגל מפות, בהיר / כהה) - אריחים וקטוריים של OpenFreeMap,
 * שמגיעים מהשרת שלנו בירושלים (/tiles), עם השמות בעברית
 */
object MapStyle {
    const val TILES = "https://tzofar-app.duckdns.org/tiles/"

    private fun arr(vararg v: Any): JSONArray = JSONArray().apply { v.forEach { put(it) } }

    fun json(dark: Boolean): String {
        val c = if (dark) mapOf(
            "bg" to "#2C3042", "urban" to "#262A3A", "water" to "#17263C", "park" to "#263C3F", "wood" to "#2A3D3C", "building" to "#30354A",
            "minor" to "#3E465A", "road" to "#4A5468", "main" to "#56627A", "motorway" to "#6B7A99", "casing" to "#262A3A",
            "rail" to "#4A5060", "border" to "#5C6478", "road_label" to "#9AA0A6", "halo" to "#1E2130", "place" to "#D0D3DA")
        else mapOf(
            "bg" to "#F5F1E8", "urban" to "#EFEBE3", "water" to "#AADAFF", "park" to "#CDE8C5", "wood" to "#D3E7C6", "building" to "#E8E3DA",
            "minor" to "#FFFFFF", "road" to "#FFFFFF", "main" to "#FFFFFF", "motorway" to "#FCE6A9", "casing" to "#D9D3C7",
            "rail" to "#C9C4BB", "border" to "#B4AFA6", "road_label" to "#70757A", "halo" to "#FFFFFF", "place" to "#3C4043")
        val name = arr("coalesce", arr("get", "name:he"), arr("get", "name"))
        val font = arr("Noto Sans Regular")
        fun inClass(vararg v: String) = arr("in", arr("get", "class"), arr("literal", arr(*v)))
        fun layer(id: String, type: String, srcLayer: String?, paint: JSONObject, filter: JSONArray? = null,
                  minzoom: Int = 0, layout: JSONObject? = null) = JSONObject().apply {
            put("id", id); put("type", type)
            if (srcLayer != null) { put("source", "omt"); put("source-layer", srcLayer) }
            if (minzoom > 0) put("minzoom", minzoom)
            if (filter != null) put("filter", filter)
            if (layout != null) put("layout", layout)
            put("paint", paint)
        }
        fun road(id: String, classes: Array<String>, color: String, w: DoubleArray, minzoom: Int = 0) =
            layer(id, "line", "transportation", JSONObject()
                .put("line-color", color)
                .put("line-width", arr("interpolate", arr("exponential", 1.5), arr("zoom"), 8, w[0], 12, w[1], 16, w[2], 18, w[3])),
                inClass(*classes), minzoom, JSONObject().put("line-cap", "round").put("line-join", "round"))
        val layers = arr(
            JSONObject().put("id", "bg").put("type", "background").put("paint", JSONObject().put("background-color", c["bg"])),
            layer("urban", "fill", "landuse", JSONObject().put("fill-color", c["urban"]),
                inClass("residential", "suburb", "neighbourhood", "commercial", "industrial", "retail")),
            layer("wood", "fill", "landcover", JSONObject().put("fill-color", c["wood"]).put("fill-opacity", 0.6),
                inClass("wood", "forest", "grass", "farmland")),
            layer("park", "fill", "park", JSONObject().put("fill-color", c["park"])),
            layer("water", "fill", "water", JSONObject().put("fill-color", c["water"])),
            layer("waterway", "line", "waterway", JSONObject().put("line-color", c["water"]).put("line-width", 1.2)),
            layer("building", "fill", "building", JSONObject().put("fill-color", c["building"]), minzoom = 14),
            layer("border", "line", "boundary", JSONObject().put("line-color", c["border"]).put("line-width", 1)
                .put("line-dasharray", arr(3, 2)), arr("<=", arr("get", "admin_level"), 4)),
            road("casing-main", arrayOf("motorway", "trunk", "primary", "secondary"), c["casing"]!!, doubleArrayOf(1.5, 4.0, 12.0, 22.0)),
            road("minor", arrayOf("tertiary", "minor", "service", "track"), c["minor"]!!, doubleArrayOf(0.0, 0.8, 6.0, 14.0), 11),
            road("road", arrayOf("secondary", "tertiary"), c["road"]!!, doubleArrayOf(0.5, 2.0, 9.0, 18.0), 9),
            road("main", arrayOf("primary", "trunk"), c["main"]!!, doubleArrayOf(0.8, 2.6, 10.0, 20.0)),
            road("motorway", arrayOf("motorway"), c["motorway"]!!, doubleArrayOf(1.0, 3.0, 11.0, 22.0)),
            layer("rail", "line", "transportation", JSONObject().put("line-color", c["rail"]).put("line-width", 1)
                .put("line-dasharray", arr(4, 3)), arr("==", arr("get", "class"), "rail"), 11),
            layer("road-label", "symbol", "transportation_name", JSONObject().put("text-color", c["road_label"])
                .put("text-halo-color", c["halo"]).put("text-halo-width", 1.5), minzoom = 13,
                layout = JSONObject().put("symbol-placement", "line").put("text-field", name).put("text-font", font).put("text-size", 12)),
            layer("place", "symbol", "place", JSONObject().put("text-color", c["place"])
                .put("text-halo-color", c["halo"]).put("text-halo-width", 1.5),
                inClass("city", "town", "village", "suburb", "neighbourhood"),
                layout = JSONObject().put("text-field", name).put("text-font", font)
                    .put("text-size", arr("match", arr("get", "class"), "city", 16, "town", 14, 12))),
            // מקומות קטנים (חוות, שכונות קטנות, מקומות בודדים, איים) - כשמתקרבים
            layer("place-small", "symbol", "place", JSONObject().put("text-color", c["place"])
                .put("text-halo-color", c["halo"]).put("text-halo-width", 1.5),
                inClass("hamlet", "isolated_dwelling", "quarter", "locality", "island"), minzoom = 12,
                layout = JSONObject().put("text-field", name).put("text-font", font).put("text-size", 11))
        )
        return JSONObject()
            .put("version", 8)
            .put("glyphs", TILES + "fonts/{fontstack}/{range}.pbf")
            .put("sources", JSONObject().put("omt", JSONObject().put("type", "vector").put("url", TILES + "planet")))
            .put("layers", layers)
            .toString()
    }
}
