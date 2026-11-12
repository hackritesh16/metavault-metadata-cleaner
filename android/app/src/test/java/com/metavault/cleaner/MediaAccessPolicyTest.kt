package com.metavault.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaAccessPolicyTest {
    private val policy = MediaAccessPolicy

    @Test fun legacyMediaUsesStoragePermission() {
        for (sdk in listOf(26, 29, 32)) {
            for (category in listOf(MediaAccessPolicy.Category.PHOTOS, MediaAccessPolicy.Category.VIDEOS, MediaAccessPolicy.Category.AUDIO)) {
                assertEquals(listOf(policy.STORAGE), policy.permissions(sdk, category))
            }
        }
    }

    @Test fun android13RequestsOnlyChosenCategory() {
        assertEquals(listOf(policy.IMAGES), policy.permissions(33, MediaAccessPolicy.Category.PHOTOS))
        assertEquals(listOf(policy.VIDEO), policy.permissions(33, MediaAccessPolicy.Category.VIDEOS))
        assertEquals(listOf(policy.AUDIO), policy.permissions(33, MediaAccessPolicy.Category.AUDIO))
    }

    @Test fun android14SupportsReselectingPhotosWithoutVideoAccess() {
        assertEquals(listOf(policy.IMAGES, policy.SELECTED), policy.permissions(34, MediaAccessPolicy.Category.PHOTOS))
        assertEquals(listOf(policy.VIDEO, policy.SELECTED), policy.permissions(35, MediaAccessPolicy.Category.VIDEOS))
        assertEquals(listOf(policy.AUDIO), policy.permissions(35, MediaAccessPolicy.Category.AUDIO))
    }

    @Test fun documentPickerNeverRequestsMediaOrAllFilesPermission() {
        for (sdk in listOf(26, 29, 33, 35)) {
            assertEquals(emptyList<String>(), policy.permissions(sdk, MediaAccessPolicy.Category.DOCUMENTS))
        }
    }

    @Test fun selectedVisualAccessDoesNotGrantAudio() {
        assertEquals(MediaAccessPolicy.Access.SELECTED, policy.access(34, MediaAccessPolicy.Category.PHOTOS, setOf(policy.SELECTED), false))
        assertEquals(MediaAccessPolicy.Access.NONE, policy.access(34, MediaAccessPolicy.Category.AUDIO, setOf(policy.SELECTED), false))
    }

    @Test fun fullAccessTakesPrecedenceOverSelectedAccess() {
        assertEquals(MediaAccessPolicy.Access.FULL, policy.access(35, MediaAccessPolicy.Category.PHOTOS, setOf(policy.IMAGES, policy.SELECTED), false))
        assertEquals(MediaAccessPolicy.Access.FULL, policy.access(35, MediaAccessPolicy.Category.DOCUMENTS, emptySet(), true))
    }

    @Test fun deniedAccessIsNotReportedAsScanned() {
        assertEquals(MediaAccessPolicy.Access.NONE, policy.access(33, MediaAccessPolicy.Category.VIDEOS, setOf(policy.IMAGES), false))
        assertEquals(MediaAccessPolicy.Access.PICKER, policy.access(29, MediaAccessPolicy.Category.DOCUMENTS, setOf(policy.STORAGE), false))
    }

    @Test fun browsingNeverRequestsLocationMetadata() {
        for (category in MediaAccessPolicy.Category.values()) {
            assertFalse(policy.permissions(35, category).any { it.contains("LOCATION") })
        }
    }
}
