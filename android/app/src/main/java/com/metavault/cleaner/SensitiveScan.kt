package com.metavault.cleaner

import java.util.Locale
import kotlin.math.abs

/**
 * Picks out the details that reveal where a file came from: GPS location, IP-like text and owner identity.
 * Pure Kotlin on already-extracted fields, so it runs on this device only and is unit-testable.
 */
object SensitiveScan {
    // Dotted IPv4. The look-arounds reject longer version-like numbers such as 1.2.3.4.5.
    private val IPV4 = Regex("""(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?!\d|\.\d)""")
    private val TOKEN_SPLIT = Regex("""[\s,;"'<>()\[\]{}=]+""")

    // Fields that hold versions, dates, tool names or IDs. Their digits often look like an IP by accident.
    private val SKIP_KEY = Regex("version|software|tool|agent|generator|firmware|date|time|unique|serial|id$", RegexOption.IGNORE_CASE)

    private val SENSITIVE_KEY = Regex(
        """\b(gps|location|ip address|artist|author|owner|serial|company|manager)\b|dc:creator|last modified""",
        RegexOption.IGNORE_CASE
    )

    const val IP_KEY = "Possible IP address"

    fun isSensitiveKey(key: String): Boolean = SENSITIVE_KEY.containsMatchIn(key)

    /** "28.613900° N, 77.209000° E". */
    fun formatLocation(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.6f° %s, %.6f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

    // Own IPv6 check on purpose: InetAddress.getByName falls back to a DNS lookup for text it cannot parse.
    fun isIPv6(s: String): Boolean {
        if (s.count { it == ':' } < 2) return false
        if (s.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':') }) return false
        val dbl = s.indexOf("::")
        if (dbl != s.lastIndexOf("::")) return false
        fun groupsOk(part: String) = part.isEmpty() || part.split(":").all { it.length in 1..4 }
        return if (dbl >= 0) {
            val head = s.substring(0, dbl)
            val tail = s.substring(dbl + 2)
            val h = if (head.isEmpty()) 0 else head.split(":").size
            val t = if (tail.isEmpty()) 0 else tail.split(":").size
            groupsOk(head) && groupsOk(tail) && h + t <= 7
        } else {
            val g = s.split(":")
            g.size == 8 && g.all { it.length in 1..4 }
        }
    }

    /** One entry per distinct address, like "203.0.113.42  (in Description)". */
    fun findIps(fields: List<MetaField>): List<String> {
        val found = linkedMapOf<String, String>()
        for (f in fields) {
            if (SKIP_KEY.containsMatchIn(f.key)) continue
            IPV4.findAll(f.value).forEach { found.putIfAbsent(it.value, f.key) }
            f.value.split(TOKEN_SPLIT).forEach { raw ->
                val token = raw.trimEnd('.', ',', ';')
                if (isIPv6(token)) found.putIfAbsent(token, f.key)
            }
        }
        return found.map { (ip, key) -> "$ip  (in $key)" }
    }

    /** Adds an IP row when something looks like one, marks sensitive rows, and lists them first. */
    fun annotate(fields: List<MetaField>): List<MetaField> {
        val ips = findIps(fields)
        val all = if (ips.isEmpty()) fields else fields + MetaField(IP_KEY, ips.joinToString("\n"))
        val marked = all.map { if (isSensitiveKey(it.key)) it.copy(sensitive = true) else it }
        return marked.filter { it.sensitive } + marked.filterNot { it.sensitive }
    }
}
