package com.metavault.cleaner

import java.util.Locale

/** What to do when a file with the same name already exists where a copy is being saved. */
internal enum class ConflictChoice { OVERWRITE, RENAME, SKIP }

/** Where a processed file goes after the conflict question has been answered. */
internal sealed interface SaveTarget {
    /** Write [name]. When [replaceExisting] is true the file already there is replaced. */
    data class Write(val name: String, val replaceExisting: Boolean) : SaveTarget
    object Skip : SaveTarget
}

internal const val CLEAN_SUFFIX = "_clean"

/** "photo.jpg" gives ("photo", "jpg"). A leading dot or no dot means no extension. */
internal fun splitName(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot + 1)
}

private fun join(base: String, ext: String) = if (ext.isEmpty()) base else "$base.$ext"

/** "photo.jpg" gives "photo_clean.jpg". A name that already ends in _clean is left alone, so a second click does nothing. */
internal fun withCleanSuffix(name: String): String {
    val (base, ext) = splitName(name)
    return if (base.endsWith(CLEAN_SUFFIX)) name else join(base + CLEAN_SUFFIX, ext)
}

/** Same as [withCleanSuffix] for a name typed without its extension. */
internal fun withCleanBase(base: String): String = if (base.endsWith(CLEAN_SUFFIX)) base else base + CLEAN_SUFFIX

/** Makes a typed name safe to use as a file name: no path separators or reserved characters, never blank. */
internal fun cleanTypedName(name: String, fallback: String): String {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimStart('.')
    return safe.ifBlank { fallback }
}

/** "photo_clean.jpg" gives "photo_clean (1).jpg", then "(2)" and so on, until [taken] says the name is free. */
internal fun uniqueName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val (base, ext) = splitName(name)
    var n = 1
    while (n < 10_000) {
        val candidate = join("$base ($n)", ext)
        if (!taken(candidate)) return candidate
        n++
    }
    error("Could not find a free file name")
}

/** Names are compared without regard to case, because some storage providers do the same. */
internal fun nameKey(name: String): String = name.lowercase(Locale.ROOT)

/**
 * Applies the answer to the conflict question. [exists] says whether [desired] is already taken.
 * [choice] is only read when it is.
 */
internal fun decideTarget(desired: String, exists: Boolean, choice: ConflictChoice?, taken: (String) -> Boolean): SaveTarget {
    if (!exists) return SaveTarget.Write(desired, replaceExisting = false)
    return when (choice ?: ConflictChoice.RENAME) {
        ConflictChoice.OVERWRITE -> SaveTarget.Write(desired, replaceExisting = true)
        ConflictChoice.RENAME -> SaveTarget.Write(uniqueName(desired, taken), replaceExisting = false)
        ConflictChoice.SKIP -> SaveTarget.Skip
    }
}

/** Default output name for a batch item: the original name with _clean added. */
internal fun defaultOutputName(originalName: String): String =
    withCleanSuffix(cleanTypedName(originalName, "metavault-file"))
