package com.deadliner.sync

import com.deadliner.platform.WidgetUpdateReason
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetAwareRepositoryMutationNotifierTest {
    @Test
    fun `forwards mutation before requesting widget refresh`() {
        val events = mutableListOf<String>()
        val notifier = WidgetAwareRepositoryMutationNotifier(
            delegate = RepositoryMutationNotifier { events += "repository" },
            requestWidgetUpdate = { reason -> events += "widget:$reason" },
        )

        notifier.notifyMutation()

        assertEquals(
            listOf("repository", "widget:${WidgetUpdateReason.DATA_MUTATION}"),
            events,
        )
    }

    @Test
    fun `widget failure does not escape repository mutation`() {
        var repositoryNotifications = 0
        val notifier = WidgetAwareRepositoryMutationNotifier(
            delegate = RepositoryMutationNotifier { repositoryNotifications++ },
            requestWidgetUpdate = { error("widget host unavailable") },
        )

        notifier.notifyMutation()

        assertEquals(1, repositoryNotifications)
    }
}
