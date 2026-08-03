package com.deadliner.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavV2UpgradeOnlyTest {
    @Test
    fun `only an existing V2 blob qualifies a provider for legacy import`() {
        assertFalse(hasLegacyV2Payload(null, null, null))
        assertTrue(hasLegacyV2Payload("", null, null))
        assertTrue(hasLegacyV2Payload(null, "{}", null))
        assertTrue(hasLegacyV2Payload(null, null, "{}"))
    }
}
