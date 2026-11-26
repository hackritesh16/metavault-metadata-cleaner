package com.metavault.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraThemeTest {
    @Test fun everyThemeHasItsOwnColours() {
        val palettes = AuroraThemeNames.map { paletteFor(it) }
        assertEquals(8, palettes.size)
        assertEquals(palettes.size, palettes.map { it.accent }.toSet().size)
        assertEquals(palettes.size, palettes.map { it.bg }.toSet().size)
    }

    @Test fun surfacesAreLighterThanTheBackground() {
        AuroraThemeNames.forEach { name ->
            val p = paletteFor(name)
            assertTrue("$name surface", p.surface1.red + p.surface1.green + p.surface1.blue >= p.bg.red + p.bg.green + p.bg.blue)
        }
    }

    @Test fun unknownNameFallsBackToAurora() {
        assertEquals(paletteFor("Aurora").accent, paletteFor("nope").accent)
    }
}
