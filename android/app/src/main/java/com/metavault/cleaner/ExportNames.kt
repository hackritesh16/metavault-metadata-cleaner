package com.metavault.cleaner

internal fun cleanStem(stem: String): String =
    if (stem.endsWith("_clean", ignoreCase = true)) stem else "${stem}_clean"

internal fun validStem(stem: String): Boolean = stem.isNotBlank() && stem == stem.trim() &&
    stem != "." && stem != ".." && stem.none { it.code < 32 || it in "\\/:*?\"<>|" }

internal sealed interface CollisionChoice {
    data object Overwrite : CollisionChoice
    data object Skip : CollisionChoice
    data class Rename(val stem: String) : CollisionChoice
}

internal class NameUnavailable : Exception("The provider reserved this name")

internal data class NameClash(val name: String, val stem: String, val ext: String, val canOverwrite: Boolean)

/** Re-query after the user decides: a file provider or another batch item can change the folder. */
internal suspend fun <T> exportNamed(
    stem: String, ext: String,
    find: (String) -> List<T>, replaceable: (T) -> Boolean,
    decide: suspend (NameClash) -> CollisionChoice,
    create: (String) -> String, replace: (T) -> String
): String? {
    var current = stem
    while (true) {
        require(validStem(current)) { "Enter a valid file name" }
        require(ext.isNotBlank() && validStem(ext)) { "Invalid output extension" }
        val name = "$current.$ext"
        var hits = find(name)
        if (hits.isEmpty()) {
            try {
                return create(name)
            } catch (e: NameUnavailable) {
                // Some providers auto-rename a new document when a hidden or racing duplicate exists.
                hits = find(name)
            }
        }
        val target = hits.singleOrNull()
        val allowed = target != null && replaceable(target)
        when (val choice = decide(NameClash(name, current, ext, allowed))) {
            CollisionChoice.Skip -> return null
            is CollisionChoice.Rename -> current = choice.stem
            CollisionChoice.Overwrite -> {
                check(allowed) { "Choose another name for this copy" }
                check(find(name).singleOrNull() == target && replaceable(target)) {
                    "The destination changed. Choose the folder again."
                }
                return replace(target)
            }
        }
    }
}
