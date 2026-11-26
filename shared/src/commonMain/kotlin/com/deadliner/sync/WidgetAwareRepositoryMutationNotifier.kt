package com.deadliner.sync

import com.deadliner.platform.WidgetUpdateReason
import com.deadliner.platform.updateWidget

/** Keeps repository sync authoritative while treating widget refresh as best-effort UI work. */
internal class WidgetAwareRepositoryMutationNotifier(
    private val delegate: RepositoryMutationNotifier,
    private val requestWidgetUpdate: (WidgetUpdateReason) -> Unit = ::updateWidget,
) : RepositoryMutationNotifier {
    override fun notifyMutation() {
        delegate.notifyMutation()
        runCatching {
            requestWidgetUpdate(WidgetUpdateReason.DATA_MUTATION)
        }
    }
}
