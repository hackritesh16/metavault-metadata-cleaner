package com.metavault.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveScanTest {
    private fun f(key: String, value: String) = MetaField(key, value)

    @Test fun formatsLocationWithHemispheres() {
        assertEquals("28.613900° N, 77.209000° E", SensitiveScan.formatLocation(28.6139, 77.2090))
        assertEquals("33.868800° S, 151.209300° W", SensitiveScan.formatLocation(-33.8688, -151.2093))
    }

    @Test fun findsIpv4AndIpv6InText() {
        val ips = SensitiveScan.findIps(listOf(f("Description", "Uploaded from 203.0.113.42 and 2001:db8::1")))
        assertEquals(listOf("203.0.113.42  (in Description)", "2001:db8::1  (in Description)"), ips)
    }

    @Test fun ignoresDatesTimesAndVersionNumbers() {
        val ips = SensitiveScan.findIps(
            listOf(
                f("Date taken", "2026:11:09 18:42:10"),
                f("Software", "Adobe Photoshop 25.1.0.12"),
                f("XMP · xmp:CreatorTool", "Editor 9.8.7.6"),
                f("Description", "taken at 18:42:10 on 2026:11:09, build 1.2.3.4.5")
            )
        )
        assertTrue(ips.toString(), ips.isEmpty())
    }

    @Test fun rejectsOutOfRangeOctets() {
        assertTrue(SensitiveScan.findIps(listOf(f("Description", "ref 300.1.1.1 and 1.2.3.999"))).isEmpty())
    }

    @Test fun validatesIpv6() {
        assertTrue(SensitiveScan.isIPv6("::1"))
        assertTrue(SensitiveScan.isIPv6("2001:db8::1"))
        assertTrue(SensitiveScan.isIPv6("1:2:3:4:5:6:7:8"))
        assertFalse(SensitiveScan.isIPv6("1:2:3"))
        assertFalse(SensitiveScan.isIPv6("18:42:10"))
        assertFalse(SensitiveScan.isIPv6(":::"))
        assertFalse(SensitiveScan.isIPv6("12345::1"))
        assertFalse(SensitiveScan.isIPv6("hello::world"))
    }

    @Test fun annotateMarksSensitiveRowsAndListsThemFirst() {
        val out = SensitiveScan.annotate(
            listOf(
                f("Camera model", "Pixel 8 Pro"),
                f("Location (GPS)", "28.613900° N, 77.209000° E"),
                f("Artist", "Priya Nair"),
                f("Description", "from 203.0.113.42")
            )
        )
        assertEquals(listOf("Location (GPS)", "Artist", SensitiveScan.IP_KEY, "Camera model", "Description"), out.map { it.key })
        assertEquals(listOf(true, true, true, false, false), out.map { it.sensitive })
    }

    @Test fun annotateAddsNoIpRowWhenNothingLooksLikeOne() {
        val out = SensitiveScan.annotate(listOf(f("Camera model", "Pixel 8 Pro")))
        assertEquals(1, out.size)
        assertFalse(out[0].sensitive)
    }
}
