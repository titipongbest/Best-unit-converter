@file:OptIn(ExperimentalMaterial3Api::class)

package com.bestspace.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * หน้าพราง: แอปแปลงหน่วย (Unit Converter) แบบออฟไลน์
 * – แตะปุ่มสลับหน่วย (⇄) = สลับหน่วยต้นทาง/ปลายทางตามปกติ
 * – หมวดน้ำหนัก หน่วยต้นทาง kg กรอก 45 แล้วกด = = เข้าหน้ารหัสผ่านของตู้ (ไม่บันทึกลงประวัติ)
 */
object DecoyConverter {
    const val REVEAL_CAT_ID = "weight"
    const val REVEAL_UNIT_CODE = "kg"
    const val REVEAL_VALUE = 45.0

    data class HistoryItem(
        val id: Long,
        val catId: String,
        val fromIdx: Int,
        val toIdx: Int,
        val input: String,
        val output: String,
        val fromSymbol: String,
        val toSymbol: String,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("unit_converter", Context.MODE_PRIVATE)

    fun loadHistory(ctx: Context): List<HistoryItem> = try {
        val a = JSONArray(prefs(ctx).getString("history", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            HistoryItem(
                id = o.getLong("id"),
                catId = o.getString("c"),
                fromIdx = o.getInt("fi"),
                toIdx = o.getInt("ti"),
                input = o.getString("in"),
                output = o.getString("out"),
                fromSymbol = o.getString("fs"),
                toSymbol = o.getString("ts"),
            )
        }
    } catch (_: Exception) { emptyList() }

    fun saveHistory(ctx: Context, items: List<HistoryItem>) {
        val a = JSONArray()
        items.take(30).forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("c", it.catId)
                    .put("fi", it.fromIdx)
                    .put("ti", it.toIdx)
                    .put("in", it.input)
                    .put("out", it.output)
                    .put("fs", it.fromSymbol)
                    .put("ts", it.toSymbol)
            )
        }
        prefs(ctx).edit().putString("history", a.toString()).commit()
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove("history").commit()
    }

    fun loadLastState(ctx: Context): Triple<Int, Int, Int> {
        val p = prefs(ctx)
        val cat = p.getInt("cat", 0).coerceIn(0, UnitConverterEngine.categories.lastIndex)
        val units = UnitConverterEngine.categories[cat].units
        val f = p.getInt("from", UnitConverterEngine.categories[cat].defaultFrom).coerceIn(0, units.lastIndex)
        val t = p.getInt("to", UnitConverterEngine.categories[cat].defaultTo).coerceIn(0, units.lastIndex)
        return Triple(cat, f, t)
    }

    fun saveLastState(ctx: Context, cat: Int, from: Int, to: Int) {
        prefs(ctx).edit().putInt("cat", cat).putInt("from", from).putInt("to", to).apply()
    }
}

data class UnitDef(
    val code: String,
    val symbol: String,
    val label: String,
    val toBase: (Double) -> Double,
    val fromBase: (Double) -> Double,
) {
    companion object {
        fun linear(code: String, symbol: String, label: String, factor: Double) =
            UnitDef(code, symbol, label, { it * factor }, { it / factor })
    }
}

data class UnitCategory(
    val id: String,
    val title: String,
    val units: List<UnitDef>,
    val defaultFrom: Int = 0,
    val defaultTo: Int = 1,
)

object UnitConverterEngine {
    val categories: List<UnitCategory> = listOf(
        UnitCategory(
            id = "length",
            title = "ความยาว",
            units = listOf(
                UnitDef.linear("mm", "mm", "มิลลิเมตร (mm)", 0.001),
                UnitDef.linear("cm", "cm", "เซนติเมตร (cm)", 0.01),
                UnitDef.linear("m", "m", "เมตร (m)", 1.0),
                UnitDef.linear("km", "km", "กิโลเมตร (km)", 1000.0),
                UnitDef.linear("in", "in", "นิ้ว (in)", 0.0254),
                UnitDef.linear("ft", "ft", "ฟุต (ft)", 0.3048),
                UnitDef.linear("yd", "yd", "หลา (yd)", 0.9144),
                UnitDef.linear("mi", "mi", "ไมล์ (mi)", 1609.344),
                UnitDef.linear("wa", "วา", "วา (2 เมตร)", 2.0),
            ),
            defaultFrom = 2, // m
            defaultTo = 1,   // cm
        ),
        UnitCategory(
            id = "weight",
            title = "น้ำหนัก",
            units = listOf(
                UnitDef.linear("mg", "mg", "มิลลิกรัม (mg)", 0.000001),
                UnitDef.linear("g", "g", "กรัม (g)", 0.001),
                UnitDef.linear("kg", "kg", "กิโลกรัม (kg)", 1.0),
                UnitDef.linear("t", "t", "เมตริกตัน (t)", 1000.0),
                UnitDef.linear("oz", "oz", "ออนซ์ (oz)", 0.028349523125),
                UnitDef.linear("lb", "lb", "ปอนด์ (lb)", 0.45359237),
                UnitDef.linear("baht", "บาท", "บาททอง (15.2 g)", 0.0152),
            ),
            defaultFrom = 2, // kg
            defaultTo = 5,   // lb
        ),
        UnitCategory(
            id = "temp",
            title = "อุณหภูมิ",
            units = listOf(
                UnitDef("c", "°C", "เซลเซียส (°C)", { it }, { it }),
                UnitDef("f", "°F", "ฟาเรนไฮต์ (°F)", { (it - 32.0) * 5.0 / 9.0 }, { it * 9.0 / 5.0 + 32.0 }),
                UnitDef("k", "K", "เคลวิน (K)", { it - 273.15 }, { it + 273.15 }),
            ),
            defaultFrom = 0, // °C
            defaultTo = 1,   // °F
        ),
        UnitCategory(
            id = "area",
            title = "พื้นที่",
            units = listOf(
                UnitDef.linear("cm2", "cm²", "ตร.ซม. (cm²)", 0.0001),
                UnitDef.linear("m2", "m²", "ตร.ม. (m²)", 1.0),
                UnitDef.linear("km2", "km²", "ตร.กม. (km²)", 1_000_000.0),
                UnitDef.linear("sqwa", "ตร.วา", "ตารางวา (4 m²)", 4.0),
                UnitDef.linear("ngan", "งาน", "งาน (400 m²)", 400.0),
                UnitDef.linear("rai", "ไร่", "ไร่ (1,600 m²)", 1600.0),
                UnitDef.linear("ft2", "ft²", "ตร.ฟุต (ft²)", 0.09290304),
                UnitDef.linear("acre", "ac", "เอเคอร์ (acre)", 4046.8564224),
            ),
            defaultFrom = 5, // ไร่
            defaultTo = 1,   // m²
        ),
        UnitCategory(
            id = "volume",
            title = "ปริมาตร",
            units = listOf(
                UnitDef.linear("ml", "mL", "มิลลิลิตร (mL)", 0.001),
                UnitDef.linear("l", "L", "ลิตร (L)", 1.0),
                UnitDef.linear("m3", "m³", "ลูกบาศก์เมตร (m³)", 1000.0),
                UnitDef.linear("tsp", "ช้อนชา", "ช้อนชา (tsp)", 0.00492892159375),
                UnitDef.linear("tbsp", "ช้อนโต๊ะ", "ช้อนโต๊ะ (tbsp)", 0.01478676478125),
                UnitDef.linear("cup", "ถ้วย", "ถ้วยตวง (cup)", 0.2365882365),
                UnitDef.linear("floz", "fl oz", "ฟลูอิดออนซ์ (fl oz)", 0.0295735295625),
                UnitDef.linear("gal", "gal", "แกลลอน US (gal)", 3.785411784),
            ),
            defaultFrom = 1, // L
            defaultTo = 0,   // mL
        ),
        UnitCategory(
            id = "speed",
            title = "ความเร็ว",
            units = listOf(
                UnitDef.linear("ms", "m/s", "เมตร/วินาที (m/s)", 1.0),
                UnitDef.linear("kmh", "km/h", "กม./ชม. (km/h)", 1.0 / 3.6),
                UnitDef.linear("mph", "mph", "ไมล์/ชม. (mph)", 0.44704),
                UnitDef.linear("kn", "kn", "นอต (knot)", 0.5144444444444445),
            ),
            defaultFrom = 1, // km/h
            defaultTo = 2,   // mph
        ),
        UnitCategory(
            id = "data",
            title = "ข้อมูล",
            units = listOf(
                UnitDef.linear("b", "B", "ไบต์ (B)", 1.0),
                UnitDef.linear("kb", "KB", "กิโลไบต์ (KB)", 1024.0),
                UnitDef.linear("mb", "MB", "เมกะไบต์ (MB)", 1_048_576.0),
                UnitDef.linear("gb", "GB", "กิกะไบต์ (GB)", 1_073_741_824.0),
                UnitDef.linear("tb", "TB", "เทระไบต์ (TB)", 1_099_511_627_776.0),
            ),
            defaultFrom = 3, // GB
            defaultTo = 2,   // MB
        ),
        UnitCategory(
            id = "time",
            title = "เวลา",
            units = listOf(
                UnitDef.linear("ms", "ms", "มิลลิวินาที (ms)", 0.001),
                UnitDef.linear("s", "s", "วินาที (s)", 1.0),
                UnitDef.linear("min", "min", "นาที (min)", 60.0),
                UnitDef.linear("hr", "hr", "ชั่วโมง (hr)", 3600.0),
                UnitDef.linear("day", "day", "วัน (day)", 86400.0),
                UnitDef.linear("wk", "wk", "สัปดาห์ (wk)", 604800.0),
            ),
            defaultFrom = 3, // hr
            defaultTo = 2,   // min
        ),
    )

    fun convert(value: Double, from: UnitDef, to: UnitDef): Double {
        if (from.code == to.code) return value
        return to.fromBase(from.toBase(value))
    }

    fun formatValue(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val clean = if (abs(v) < 1e-12) 0.0 else v
        val a = abs(clean)
        val symbols = DecimalFormatSymbols(Locale.US)
        if (a != 0.0 && (a >= 1e12 || a < 1e-6)) {
            return DecimalFormat("0.######E0", symbols).format(clean)
        }
        val bd = BigDecimal.valueOf(clean).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        val finalVal = if (bd.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else bd
        val df = DecimalFormat("#,##0.######", symbols)
        return df.format(finalVal)
    }

    fun formatInputDisplay(raw: String): String {
        if (raw.isEmpty() || raw == "-") return raw.ifEmpty { "0" }
        val neg = raw.startsWith("-")
        val body = if (neg) raw.substring(1) else raw
        val dot = body.indexOf('.')
        val intPart = if (dot >= 0) body.substring(0, dot) else body
        val fracPart = if (dot >= 0) body.substring(dot) else ""
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + fracPart
    }
}

/** โทนสว่างสะอาดแบบแอปเครื่องมือแปลงหน่วย – แยกจากหน้าตู้ (โทนมืด) ชัดเจน */
internal object NoteColors {
    val Bg = Color(0xFFF3F6FB)
    val Card = Color(0xFFFFFFFF)
    val KeyBg = Color(0xFFE9EFF8)
    val KeyOpBg = Color(0xFFDCE6F7)
    val Fg = Color(0xFF182030)
    val Muted = Color(0xFF6B768C)
    val Accent = Color(0xFF2563EB)
    val OnAccent = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1D4ED8)
    val Danger = Color(0xFFDC2626)
}

/** ธีม Material สว่างสำหรับหน้าแปลงหน่วย */
@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = NoteColors.Accent, onPrimary = NoteColors.OnAccent,
            background = NoteColors.Bg, onBackground = NoteColors.Fg,
            surface = NoteColors.Card, onSurface = NoteColors.Fg,
            surfaceVariant = NoteColors.KeyBg, onSurfaceVariant = NoteColors.Muted,
            surfaceContainer = NoteColors.Card, surfaceContainerHigh = NoteColors.Card,
            error = NoteColors.Danger,
        ),
        content = content,
    )
}

@Composable
fun DecoyScreen(onHold: () -> Unit, onLockNow: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember { DecoyConverter.loadLastState(ctx) }
    var catIdx by remember { mutableIntStateOf(initial.first) }
    var fromIdx by remember { mutableIntStateOf(initial.second) }
    var toIdx by remember { mutableIntStateOf(initial.third) }
    var inputRaw by remember { mutableStateOf("1") }
    var showHistory by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var copiedBanner by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<DecoyConverter.HistoryItem>().apply { addAll(DecoyConverter.loadHistory(ctx)) } }
    val locked = remember(settings) { NotesLockStore.isSet(ctx) }
    val hold by rememberUpdatedState(onHold)

    LaunchedEffect(copiedBanner) {
        if (copiedBanner) {
            delay(1400)
            copiedBanner = false
        }
    }

    if (settings) {
        NotesSettingsScreen(onClose = { settings = false }, onLockNow = { settings = false; onLockNow() })
        return
    }

    val category = UnitConverterEngine.categories[catIdx]
    val fromUnit = category.units[fromIdx.coerceIn(0, category.units.lastIndex)]
    val toUnit = category.units[toIdx.coerceIn(0, category.units.lastIndex)]

    val parsed = inputRaw.toDoubleOrNull()
    val convertedVal = parsed?.let { UnitConverterEngine.convert(it, fromUnit, toUnit) }
    val outputText = convertedVal?.let { UnitConverterEngine.formatValue(it) } ?: "0"
    val oneUnitText = UnitConverterEngine.formatValue(UnitConverterEngine.convert(1.0, fromUnit, toUnit))

    fun persistState(c: Int, f: Int, t: Int) {
        catIdx = c
        fromIdx = f
        toIdx = t
        DecoyConverter.saveLastState(ctx, c, f, t)
    }

    fun swapUnits() {
        persistState(catIdx, toIdx, fromIdx)
    }

    fun recordCurrent() {
        val v = inputRaw.toDoubleOrNull() ?: return
        val inDisp = UnitConverterEngine.formatInputDisplay(inputRaw)
        val outDisp = UnitConverterEngine.formatValue(UnitConverterEngine.convert(v, fromUnit, toUnit))
        val entry = DecoyConverter.HistoryItem(
            id = System.currentTimeMillis(),
            catId = category.id,
            fromIdx = fromIdx,
            toIdx = toIdx,
            input = inputRaw,
            output = outDisp,
            fromSymbol = fromUnit.symbol,
            toSymbol = toUnit.symbol,
        )
        if (history.firstOrNull()?.let {
                it.catId == entry.catId && it.fromIdx == entry.fromIdx && it.toIdx == entry.toIdx && it.input == entry.input
            } != true
        ) {
            history.add(0, entry)
            while (history.size > 30) history.removeAt(history.lastIndex)
            DecoyConverter.saveHistory(ctx, history.toList())
        }
        // อัปเดตแสดงข้อความบันทึกแล้วสั้นๆ เผื่อกด =
        if (inDisp.isNotEmpty()) copiedBanner = false
    }

    fun onKey(k: String) {
        when (k) {
            "C" -> inputRaw = "0"
            "⌫" -> {
                inputRaw = when {
                    inputRaw.length <= 1 || (inputRaw.length == 2 && inputRaw.startsWith("-")) -> "0"
                    else -> inputRaw.dropLast(1)
                }
            }
            "±" -> {
                inputRaw = when {
                    inputRaw == "0" -> "0"
                    inputRaw.startsWith("-") -> inputRaw.substring(1)
                    else -> "-$inputRaw"
                }
            }
            "." -> {
                if (!inputRaw.contains('.')) {
                    inputRaw = if (inputRaw.isEmpty() || inputRaw == "-") "${inputRaw}0." else "$inputRaw."
                }
            }
            "=" -> {
                if (category.id == DecoyConverter.REVEAL_CAT_ID &&
                    fromUnit.code == DecoyConverter.REVEAL_UNIT_CODE &&
                    inputRaw.toDoubleOrNull() == DecoyConverter.REVEAL_VALUE
                ) {
                    inputRaw = "0"
                    hold()
                } else {
                    recordCurrent()
                }
            }
            else -> {
                val digitsOnly = inputRaw.replace("-", "").replace(".", "")
                if (digitsOnly.length >= 13) return
                inputRaw = when {
                    inputRaw == "0" -> if (k == "00") "0" else k
                    inputRaw == "-0" -> if (k == "00") "-0" else "-$k"
                    else -> inputRaw + k
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(NoteColors.Bg)) {
        // แถบหัวเรื่อง
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Unit Converter", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = NoteColors.Fg)
                Text("ตัวแปลงหน่วย • ${category.title}", fontSize = 12.sp, color = NoteColors.Muted)
            }
            IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Filled.History, "ประวัติการแปลง", tint = NoteColors.Muted)
            }
            if (locked) {
                IconButton(onClick = onLockNow) {
                    Icon(Icons.Filled.Lock, "ล็อคแอป", tint = NoteColors.Muted)
                }
            }
            IconButton(onClick = { settings = true }) {
                Icon(Icons.Filled.Settings, "ตั้งค่า", tint = NoteColors.Muted)
            }
        }

        // แถบหมวดหมู่หน่วย
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(UnitConverterEngine.categories) { idx, cat ->
                FilterChip(
                    selected = idx == catIdx,
                    onClick = {
                        if (idx != catIdx) {
                            persistState(idx, cat.defaultFrom, cat.defaultTo)
                        }
                    },
                    label = { Text(cat.title, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NoteColors.Accent,
                        selectedLabelColor = NoteColors.OnAccent,
                        containerColor = NoteColors.Card,
                        labelColor = NoteColors.Fg,
                    ),
                )
            }
        }

        // กล่องแปลงหน่วย (ต้นทาง + ปุ่มสลับ ⇄ + ปลายทาง)
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConverterCard(
                    tag = "จาก",
                    units = category.units,
                    selectedIndex = fromIdx,
                    onSelectUnit = { persistState(catIdx, it, toIdx) },
                    valueText = UnitConverterEngine.formatInputDisplay(inputRaw),
                    symbol = fromUnit.symbol,
                    accentValue = false,
                )
                ConverterCard(
                    tag = "เป็น",
                    units = category.units,
                    selectedIndex = toIdx,
                    onSelectUnit = { persistState(catIdx, fromIdx, it) },
                    valueText = outputText,
                    symbol = toUnit.symbol,
                    accentValue = true,
                    trailingAction = {
                        IconButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("converted", "$outputText ${toUnit.symbol}"))
                                copiedBanner = true
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                if (copiedBanner) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                "คัดลอกผลลัพธ์",
                                tint = if (copiedBanner) NoteColors.Accent else NoteColors.Muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }

            // ปุ่มสลับหน่วย (⇄): สลับหน่วยต้นทาง/ปลายทาง
            Box(
                Modifier.align(Alignment.Center)
                    .size(48.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(NoteColors.Accent, CircleShape)
                    .border(3.dp, NoteColors.Bg, CircleShape)
                    .clickable { swapUnits() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.SwapHoriz, "สลับหน่วย", tint = NoteColors.OnAccent, modifier = Modifier.size(24.dp))
            }
        }

        // แถบแสดงสูตรเปรียบเทียบ 1 หน่วย
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "1 ${fromUnit.symbol} = $oneUnitText ${toUnit.symbol}",
                fontSize = 12.sp,
                color = NoteColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (copiedBanner) {
                Text("คัดลอกแล้ว", fontSize = 12.sp, color = NoteColors.Accent, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(6.dp))

        // แป้นตัวเลขในตัว (4x4)
        Surface(
            color = NoteColors.Card,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            val rows = listOf(
                listOf("7", "8", "9", "⌫"),
                listOf("4", "5", "6", "C"),
                listOf("1", "2", "3", "±"),
                listOf("00", "0", ".", "="),
            )
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in rows) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (key in row) {
                            val isPrimary = key == "="
                            val isOp = key == "⌫" || key == "C" || key == "±"
                            val bg = when {
                                isPrimary -> NoteColors.Accent
                                isOp -> NoteColors.KeyOpBg
                                else -> NoteColors.KeyBg
                            }
                            val fg = when {
                                isPrimary -> NoteColors.OnAccent
                                key == "C" -> NoteColors.Danger
                                isOp -> NoteColors.Ink
                                else -> NoteColors.Fg
                            }
                            Box(
                                Modifier.weight(1f).fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(bg)
                                    .clickable { onKey(key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    key,
                                    fontSize = if (key == "00") 19.sp else 22.sp,
                                    fontWeight = if (isPrimary || isOp) FontWeight.SemiBold else FontWeight.Medium,
                                    color = fg,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ประวัติการแปลง", Modifier.weight(1f))
                    if (history.isNotEmpty()) {
                        IconButton(onClick = {
                            history.clear()
                            DecoyConverter.clearHistory(ctx)
                        }) {
                            Icon(Icons.Filled.DeleteSweep, "ล้างประวัติ", tint = NoteColors.Muted)
                        }
                    }
                }
            },
            text = {
                if (history.isEmpty()) {
                    Text("ยังไม่มีประวัติ\nกดปุ่ม = เพื่อบันทึกการแปลงที่ใช้งานบ่อย", color = NoteColors.Muted, fontSize = 14.sp)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(history, key = { it.id }) { h ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        val ci = UnitConverterEngine.categories.indexOfFirst { it.id == h.catId }
                                        if (ci >= 0) {
                                            val maxU = UnitConverterEngine.categories[ci].units.lastIndex
                                            persistState(ci, h.fromIdx.coerceIn(0, maxU), h.toIdx.coerceIn(0, maxU))
                                            inputRaw = h.input
                                        }
                                        showHistory = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${UnitConverterEngine.formatInputDisplay(h.input)} ${h.fromSymbol}  →  ${h.output} ${h.toSymbol}",
                                        fontWeight = FontWeight.Medium,
                                        color = NoteColors.Fg,
                                        fontSize = 14.sp,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        history.removeAll { it.id == h.id }
                                        DecoyConverter.saveHistory(ctx, history.toList())
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Filled.Close, "ลบ", tint = NoteColors.Muted, modifier = Modifier.size(16.dp))
                                }
                            }
                            HorizontalDivider(color = NoteColors.KeyBg)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("ปิด") } },
        )
    }
}

@Composable
private fun ConverterCard(
    tag: String,
    units: List<UnitDef>,
    selectedIndex: Int,
    onSelectUnit: (Int) -> Unit,
    valueText: String,
    symbol: String,
    accentValue: Boolean,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cur = units[selectedIndex.coerceIn(0, units.lastIndex)]
    Surface(
        color = NoteColors.Card,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tag, fontSize = 12.sp, color = NoteColors.Muted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(NoteColors.KeyBg)
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            cur.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = NoteColors.Fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Filled.ArrowDropDown, null, tint = NoteColors.Muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        units.forEachIndexed { idx, u ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        u.label,
                                        fontWeight = if (idx == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                                        color = if (idx == selectedIndex) NoteColors.Accent else NoteColors.Fg,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onSelectUnit(idx)
                                },
                            )
                        }
                    }
                }
                trailingAction?.invoke()
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
                Text(
                    valueText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (accentValue) NoteColors.Accent else NoteColors.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    symbol,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = NoteColors.Muted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}
