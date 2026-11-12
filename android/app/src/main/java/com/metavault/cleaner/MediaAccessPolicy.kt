package com.metavault.cleaner

/** Pure permission policy, independent of Activity callbacks and Compose state. */
internal object MediaAccessPolicy {
    enum class Category { PHOTOS, VIDEOS, AUDIO, DOCUMENTS }
    enum class Access { FULL, SELECTED, NONE, PICKER }

    const val STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun permissions(sdk: Int, category: Category): List<String> {
        if (category == Category.DOCUMENTS) return emptyList()
        if (sdk < 33) return listOf(STORAGE)
        val primary = when (category) {
            Category.PHOTOS -> IMAGES
            Category.VIDEOS -> VIDEO
            Category.AUDIO -> AUDIO
            Category.DOCUMENTS -> return emptyList()
        }
        return if (sdk >= 34 && category != Category.AUDIO) listOf(primary, SELECTED) else listOf(primary)
    }

    fun access(sdk: Int, category: Category, granted: Set<String>, allFiles: Boolean): Access {
        if (allFiles) return Access.FULL
        if (category == Category.DOCUMENTS) {
            return if (sdk <= 28 && STORAGE in granted) Access.FULL else Access.PICKER
        }
        if (permissions(sdk, category).first() in granted) return Access.FULL
        if (sdk >= 34 && category != Category.AUDIO && SELECTED in granted) return Access.SELECTED
        return Access.NONE
    }
}
