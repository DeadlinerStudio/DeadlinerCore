package com.deadliner.domain.category

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryMutationTest {
    @Test
    fun `normalizes legacy platform icon keys to portable semantic IDs`() {
        assertEquals("briefcase", CategoryMutation.normalizedIconKey("briefcase.fill"))
        assertEquals("reading", CategoryMutation.normalizedIconKey("text.book.closed.fill"))
        assertEquals("folder", CategoryMutation.normalizedIconKey("not-a-portable-icon"))
    }

    @Test
    fun `normalizes category colors to uppercase six digit hex`() {
        assertEquals("#3B82F6", CategoryMutation.normalizedColorHex("#3b82f6"))
        assertEquals("#3B82F6", CategoryMutation.normalizedColorHex("blue"))
    }
}
