package com.bestspace.vault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitConverterTest {
    private fun unit(catId: String, code: String): UnitDef =
        UnitConverterEngine.categories.first { it.id == catId }.units.first { it.code == code }

    @Test
    fun lengthAndThaiUnits() {
        assertEquals(100.0, UnitConverterEngine.convert(1.0, unit("length", "m"), unit("length", "cm")), 1e-9)
        assertEquals(1000.0, UnitConverterEngine.convert(1.0, unit("length", "km"), unit("length", "m")), 1e-9)
        assertEquals(2.0, UnitConverterEngine.convert(1.0, unit("length", "wa"), unit("length", "m")), 1e-9)
        assertEquals(2.54, UnitConverterEngine.convert(1.0, unit("length", "in"), unit("length", "cm")), 1e-9)
    }

    @Test
    fun temperatureConversions() {
        assertEquals(32.0, UnitConverterEngine.convert(0.0, unit("temp", "c"), unit("temp", "f")), 1e-9)
        assertEquals(212.0, UnitConverterEngine.convert(100.0, unit("temp", "c"), unit("temp", "f")), 1e-9)
        assertEquals(-40.0, UnitConverterEngine.convert(-40.0, unit("temp", "c"), unit("temp", "f")), 1e-9)
        assertEquals(273.15, UnitConverterEngine.convert(0.0, unit("temp", "c"), unit("temp", "k")), 1e-9)
        assertEquals(0.0, UnitConverterEngine.convert(32.0, unit("temp", "f"), unit("temp", "c")), 1e-9)
    }

    @Test
    fun areaDataWeightTimeSpeed() {
        assertEquals(1600.0, UnitConverterEngine.convert(1.0, unit("area", "rai"), unit("area", "m2")), 1e-9)
        assertEquals(4.0, UnitConverterEngine.convert(1.0, unit("area", "rai"), unit("area", "ngan")), 1e-9)
        assertEquals(1024.0, UnitConverterEngine.convert(1.0, unit("data", "gb"), unit("data", "mb")), 1e-9)
        assertEquals(60.0, UnitConverterEngine.convert(1.0, unit("time", "hr"), unit("time", "min")), 1e-9)
        assertEquals(10.0, UnitConverterEngine.convert(36.0, unit("speed", "kmh"), unit("speed", "ms")), 1e-9)
        assertEquals(15.2, UnitConverterEngine.convert(1.0, unit("weight", "baht"), unit("weight", "g")), 1e-9)
    }

    @Test
    fun formattingAndRevealTrigger() {
        assertEquals("1,000", UnitConverterEngine.formatValue(1000.0))
        assertEquals("0", UnitConverterEngine.formatValue(-0.0))
        assertEquals("1,234.5", UnitConverterEngine.formatInputDisplay("1234.5"))
        assertEquals("weight", DecoyConverter.REVEAL_CAT_ID)
        assertEquals("kg", DecoyConverter.REVEAL_UNIT_CODE)
        assertEquals(45.0, DecoyConverter.REVEAL_VALUE, 1e-9)
        assertTrue(UnitConverterEngine.categories.any { it.id == DecoyConverter.REVEAL_CAT_ID && it.units.any { u -> u.code == DecoyConverter.REVEAL_UNIT_CODE } })
    }
}
