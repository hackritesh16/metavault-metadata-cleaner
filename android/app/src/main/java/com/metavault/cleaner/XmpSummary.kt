package com.metavault.cleaner

/** Pulls readable key/value pairs out of an XMP packet without a full XML parser (safe on malformed input). */
object XmpSummary {
    private val leaf = Regex("<([A-Za-z][\\w.-]*:[\\w.-]+)(?:\\s[^<>]*)?>([^<>]{1,400})</\\1>")
    private val attr = Regex("\\s([A-Za-z][\\w.-]*:[\\w.-]+)=\"([^\"]{1,400})\"")

    fun fields(xml: String, max: Int = 60): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (m in attr.findAll(xml)) {
            val key = m.groupValues[1]
            if (key.startsWith("xmlns") || key == "rdf:about" || key == "x:xmptk") continue
            add(out, key, m.groupValues[2])
            if (out.size >= max) return out.toList()
        }
        for (m in leaf.findAll(xml)) {
            val key = m.groupValues[1]
            add(out, if (key == "rdf:li") "item" else key, m.groupValues[2])
            if (out.size >= max) break
        }
        return out.toList()
    }

    private fun add(out: MutableSet<Pair<String, String>>, key: String, raw: String) {
        val value = unescape(raw).trim()
        if (value.isNotEmpty()) out += key to value
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
