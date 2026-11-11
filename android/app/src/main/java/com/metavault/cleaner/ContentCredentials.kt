package com.metavault.cleaner

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class C2paSummary(val fields: List<MetaField>, val aiNote: String?)

/** Minimal CBOR decoder (definite and indefinite arrays/maps). Bounds-checked, depth-limited. */
internal object Cbor {
    class Tagged(val tag: Long, val value: Any?)

    fun decode(d: ByteArray, from: Int, to: Int): Any? = Reader(d, from, minOf(to, d.size)).read(0)

    private class Reader(val d: ByteArray, var p: Int, val end: Int) {
        fun u8(): Int { if (p >= end) throw IllegalArgumentException("cbor: unexpected end"); return d[p++].toInt() and 0xFF }
        fun uint(ai: Int): Long = when {
            ai < 24 -> ai.toLong()
            ai in 24..27 -> { var v = 0L; repeat(1 shl (ai - 24)) { v = (v shl 8) or u8().toLong() }; v }
            else -> throw IllegalArgumentException("cbor: bad length")
        }
        fun peekBreak(): Boolean { if (p >= end) throw IllegalArgumentException("cbor: unexpected end"); return (d[p].toInt() and 0xFF) == 0xFF }
        fun read(depth: Int): Any? {
            if (depth > 24) throw IllegalArgumentException("cbor: too deep")
            val b = u8(); val major = b shr 5; val ai = b and 31
            return when (major) {
                0 -> uint(ai)
                1 -> -1 - uint(ai)
                2, 3 -> {
                    val len = uint(ai)
                    if (len < 0 || len > end - p) throw IllegalArgumentException("cbor: bad string length")
                    val bytes = d.copyOfRange(p, p + len.toInt()); p += len.toInt()
                    if (major == 2) bytes else String(bytes, Charsets.UTF_8)
                }
                4 -> {
                    val out = ArrayList<Any?>()
                    if (ai == 31) { while (!peekBreak()) out += read(depth + 1); p++ }
                    else { val n = uint(ai); if (n < 0 || n > end - p) throw IllegalArgumentException("cbor: bad array"); repeat(n.toInt()) { out += read(depth + 1) } }
                    out
                }
                5 -> {
                    val out = LinkedHashMap<Any?, Any?>()
                    if (ai == 31) { while (!peekBreak()) { val k = read(depth + 1); out[k] = read(depth + 1) }; p++ }
                    else { val n = uint(ai); if (n < 0 || n > end - p) throw IllegalArgumentException("cbor: bad map"); repeat(n.toInt()) { val k = read(depth + 1); out[k] = read(depth + 1) } }
                    out
                }
                6 -> Tagged(uint(ai), read(depth + 1))
                else -> when (ai) {
                    20 -> false; 21 -> true; 22, 23 -> null
                    25 -> { p += 2; if (p > end) throw IllegalArgumentException("cbor: eof"); null }
                    26 -> { p += 4; if (p > end) throw IllegalArgumentException("cbor: eof"); null }
                    27 -> { p += 8; if (p > end) throw IllegalArgumentException("cbor: eof"); null }
                    else -> null
                }
            }
        }
    }
}

/** Reads C2PA "Content Credentials" (JUMBF boxes containing CBOR) and summarises them. */
object ContentCredentials {
    private class Collector {
        val items = ArrayList<Pair<String, Any?>>()
        val binaries = ArrayList<Pair<String, Int>>()
    }

    /** Parses JUMBF boxes in d[from, to). Returns null when nothing C2PA-like was found. */
    fun parse(d: ByteArray, from: Int, to: Int): C2paSummary? {
        val col = Collector()
        runCatching { boxes(d, from, to, "", 0, col) }
        if (col.items.isEmpty() && col.binaries.isEmpty()) return null
        return summarise(col)
    }

    private fun u32(d: ByteArray, i: Int): Long =
        ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)

    private fun boxes(d: ByteArray, from: Int, to: Int, path: String, depth: Int, col: Collector) {
        if (depth > 8) return
        var pos = from
        while (pos + 8 <= to) {
            var size = u32(d, pos); val type = String(d, pos + 4, 4, Charsets.ISO_8859_1); var hdr = 8
            if (size == 1L) { if (pos + 16 > to) return; size = (u32(d, pos + 8) shl 32) or u32(d, pos + 12); hdr = 16 }
            else if (size == 0L) size = (to - pos).toLong()
            if (size < hdr || pos + size > to) return
            val ps = pos + hdr; val pe = (pos + size).toInt()
            when (type) {
                "jumb" -> {
                    var label = ""; var child = ps
                    if (ps + 8 <= pe && String(d, ps + 4, 4, Charsets.ISO_8859_1) == "jumd") {
                        val jsize = u32(d, ps).toInt()
                        if (jsize >= 8 && ps + jsize <= pe) {
                            val toggles = d[ps + 8 + 16].toInt()
                            if (jsize > 8 + 17 && toggles and 2 != 0) {
                                val ls = ps + 8 + 17; var le = ls
                                while (le < ps + jsize && d[le].toInt() != 0) le++
                                label = String(d, ls, le - ls, Charsets.UTF_8)
                            }
                            child = ps + jsize
                        }
                    }
                    boxes(d, child, pe, "$path/$label", depth + 1, col)
                }
                "cbor" -> col.items += path to runCatching { Cbor.decode(d, ps, pe) }.getOrNull()
                "bfdb", "bidb", "json" -> col.binaries += path to (pe - ps)
            }
            pos = pe
        }
    }

    private fun unwrap(x: Any?): Any? = if (x is Cbor.Tagged) unwrap(x.value) else x
    @Suppress("UNCHECKED_CAST") private fun asMap(x: Any?): Map<Any?, Any?>? = unwrap(x) as? Map<Any?, Any?>
    @Suppress("UNCHECKED_CAST") private fun asList(x: Any?): List<Any?>? = unwrap(x) as? List<Any?>
    private fun str(x: Any?): String? = (unwrap(x) as? String)?.takeIf { it.isNotBlank() }
    private fun byteSize(x: Any?): Int = when (val v = unwrap(x)) {
        is ByteArray -> v.size
        is List<*> -> v.sumOf { byteSize(it) }
        is Map<*, *> -> v.values.sumOf { byteSize(it) }
        else -> 0
    }

    private fun agent(x: Any?): String? {
        str(x)?.let { return it }
        val m = asMap(x) ?: return null
        return listOfNotNull(str(m["name"]), str(m["version"])).joinToString(" ").ifBlank { null }
    }

    private val algNames = mapOf(-7L to "ES256", -35L to "ES384", -36L to "ES512", -8L to "EdDSA", -37L to "PS256", -38L to "PS384", -39L to "PS512")

    private fun summarise(col: Collector): C2paSummary {
        val out = ArrayList<MetaField>()
        val g = "Content Credentials (C2PA)"
        fun add(n: String, v: String?) { if (!v.isNullOrBlank()) out += MetaField(g, n, v.take(300)) }

        var generator: String? = null; var software: String? = null; var aiType: String? = null
        val actions = ArrayList<String>()
        var title: String? = null; var instanceId: String? = null; var claimAlg: String? = null
        val manifest = (col.items.map { it.first } + col.binaries.map { it.first }).flatMap { it.split('/') }.firstOrNull { it.startsWith("urn:") }
        val assertionLabels = LinkedHashSet<String>()
        for ((p, _) in col.items + col.binaries.map { it.first to null }) {
            val segs = p.split('/')
            val i = segs.indexOf("c2pa.assertions")
            if (i >= 0 && i + 1 < segs.size) assertionLabels += segs[i + 1]
        }

        for ((path, value) in col.items) {
            val m = asMap(value)
            when {
                path.endsWith("/c2pa.claim") || path.endsWith("/c2pa.claim.v2") || path.contains("/c2pa.claim") && !path.contains("signature") -> if (m != null) {
                    val info = m["claim_generator_info"]
                    generator = generator ?: (asList(info)?.firstNotNullOfOrNull { agent(it) } ?: agent(info) ?: str(m["claim_generator"]))
                    title = title ?: str(m["dc:title"]); instanceId = instanceId ?: str(m["instanceID"]) ?: str(m["instance_id"])
                    claimAlg = claimAlg ?: str(m["alg"])
                }
                path.contains("c2pa.actions") -> if (m != null) asList(m["actions"])?.forEach { a ->
                    val am = asMap(a) ?: return@forEach
                    val name = str(am["action"]) ?: return@forEach
                    val whenAt = str(am["when"])
                    actions += if (whenAt != null) "$name at $whenAt" else name
                    software = software ?: agent(am["softwareAgent"]) ?: agent(asMap(am["parameters"])?.get("softwareAgent"))
                    aiType = aiType ?: str(am["digitalSourceType"])
                }
                path.contains("c2pa.hash.data") -> if (m != null) {
                    val ex = asList(m["exclusions"])?.firstNotNullOfOrNull { asMap(it) }
                    val alg = str(m["alg"])
                    val range = ex?.let { e -> "excludes ${unwrap(e["length"])} bytes at offset ${unwrap(e["start"])}" }
                    add("Hash binding", listOfNotNull(alg, range).joinToString(", "))
                }
            }
        }

        val ai = aiType?.takeIf { it.contains("AlgorithmicMedia", ignoreCase = true) }
        val who = software ?: generator
        val aiNote = ai?.let { "Content Credentials declare this image as AI-generated" + (who?.let { " ($it)" } ?: "") + "." }
        if (ai != null) out.add(0, MetaField(g, "Declared origin", "AI-generated (${ai.substringAfterLast('/')})"))
        add("Software agent", software); add("Claim generator", generator)
        actions.take(10).forEach { add("Action", it) }
        add("Title", title); add("Instance ID", instanceId); add("Manifest", manifest)

        col.items.firstOrNull { it.first.endsWith("c2pa.signature") }?.second?.let { sigVal ->
            val arr = asList(sigVal)
            if (arr != null && arr.size >= 2) {
                val protectedHdr = (unwrap(arr[0]) as? ByteArray)?.let { runCatching { asMap(Cbor.decode(it, 0, it.size)) }.getOrNull() }
                (unwrap(protectedHdr?.get(1L)) as? Long)?.let { add("Signature algorithm", algNames[it] ?: "COSE alg $it") }
                val un = asMap(arr[1])
                val certs = asList(un?.get(33L))?.mapNotNull { unwrap(it) as? ByteArray }.orEmpty()
                if (certs.isNotEmpty()) {
                    add("Certificates", "${certs.size} in chain")
                    runCatching {
                        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certs[0])) as X509Certificate
                        add("Signed by", cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC1779))
                        add("Issuer", cert.issuerX500Principal.getName(javax.security.auth.x500.X500Principal.RFC1779))
                    }
                }
                un?.get("sigTst2")?.let { add("Trusted timestamp", "present (${byteSize(it)} bytes)") }
                un?.get("rVals")?.let { add("Revocation (OCSP) data", "present (${byteSize(it)} bytes)") }
            }
            add("Signature", "present")
        }
        if (assertionLabels.isNotEmpty()) add("Assertions", assertionLabels.joinToString(", "))
        return C2paSummary(out, aiNote)
    }
}
