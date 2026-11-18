package com.tal.redalert

import android.content.Context
import android.graphics.Color

/**
 * פלטות צבעים (הגדרות ← תצוגה ← פלטת צבעים): הגוון של המשטחים - רקע, כרטיסים, קווים, פס החיוויים.
 * פלטה לחשוך ופלטה לבהיר, כל אחת לבחירה בנפרד. הטקסט והצבעים של המצבים (ירוק/אדום/כתום) לא משתנים
 */
data class Palette(val id: String, val name: String, val bg: String, val chip: String, val card: String,
                   val div: String, val sepv: String, val bar: String) {
    fun c(h: String) = Color.parseColor(h)
}

object Palettes {
    val DARK = listOf(
        Palette("black", "שחור", "#000000", "#2C2C2E", "#1B1B1D", "#2A2A2C", "#5C5C5E", "#121214"),
        Palette("night", "כחול לילה", "#0D1424", "#22304D", "#18223A", "#26324D", "#3A4866", "#111A2E"),
        Palette("graphite", "גרפיט", "#17181C", "#33353B", "#24262B", "#33353B", "#55575E", "#1F2024"),
        Palette("ocean", "אוקיינוס", "#03223F", "#0F4670", "#08355A", "#0F4670", "#2C6690", "#052B4C"),
        Palette("emerald", "אזמרגד", "#062A26", "#145049", "#0C3F38", "#14564D", "#2A6E65", "#08332E"),
        Palette("forest", "ירוק יער", "#0F1A12", "#2A4430", "#1B2E1F", "#2A4430", "#46624C", "#142318"),
        Palette("purple", "סגול מלכותי", "#140C24", "#34245A", "#24183F", "#34245A", "#52407E", "#1A1030"),
        Palette("burgundy", "בורדו", "#1A0B10", "#40202C", "#2E1520", "#40202C", "#6A3A4A", "#22101A"),
        Palette("chocolate", "שוקולד", "#17120B", "#3A2C1C", "#2A2014", "#3A2C1C", "#5E4A30", "#1F180F"),
    )
    val LIGHT = listOf(
        Palette("regular", "רגיל", "#F2F2F7", "#E5E5EA", "#FFFFFF", "#E0E0E5", "#C7C7CC", "#FFFFFF"),
        Palette("sky", "תכלת", "#EAF6FC", "#D3ECF7", "#FFFFFF", "#D6E9F2", "#AFCBDA", "#FFFFFF"),
        Palette("lavender", "לבנדר", "#F1ECFB", "#E2D8F6", "#FFFFFF", "#E4DCF4", "#C3B5E3", "#FFFFFF"),
        Palette("mint", "מנטה", "#E6F7F1", "#CDEEE2", "#FFFFFF", "#D3EBE2", "#A9D6C5", "#FFFFFF"),
        Palette("peach", "אפרסק", "#FFF1E8", "#FBDCC9", "#FFFFFF", "#F3DFD2", "#DDB89F", "#FFFFFF"),
        Palette("pink", "ורוד", "#FCEEF3", "#F6D6E2", "#FFFFFF", "#F0DCE4", "#D9B1C2", "#FFFFFF"),
    )
    fun dark(c: Context) = DARK.firstOrNull { it.id == Prefs.darkPalette(c) } ?: DARK[0]
    fun light(c: Context) = LIGHT.firstOrNull { it.id == Prefs.lightPalette(c) } ?: LIGHT[0]
    fun current(c: Context, dark: Boolean) = if (dark) dark(c) else light(c)
}
