package com.deadliner.viewmodel.intro

import kotlin.test.Test
import kotlin.test.assertEquals

class IntroWizardViewModelTest {
    @Test
    fun `walkthrough follows the v5 interaction order and remains done`() {
        val viewModel = IntroWizardViewModel()

        val visited = buildList {
            add(viewModel.state.value.currentStep)
            repeat(7) {
                viewModel.next()
                add(viewModel.state.value.currentStep)
            }
        }

        assertEquals(
            listOf(
                IntroWizardStep.AddEntry,
                IntroWizardStep.AddEntryInfo,
                IntroWizardStep.SwipeRightComplete,
                IntroWizardStep.SwipeLeftDelete,
                IntroWizardStep.AiEntry,
                IntroWizardStep.AiInfo,
                IntroWizardStep.Done,
                IntroWizardStep.Done,
            ),
            visited,
        )
    }

    @Test
    fun `named intents preserve event-specific transitions`() {
        val viewModel = IntroWizardViewModel()

        viewModel.onAddEntryClicked()
        assertEquals(IntroWizardStep.AddEntryInfo, viewModel.state.value.currentStep)
        viewModel.onAddEntryInfoNext()
        assertEquals(IntroWizardStep.SwipeRightComplete, viewModel.state.value.currentStep)
        viewModel.onSwipeRightComplete()
        assertEquals(IntroWizardStep.SwipeLeftDelete, viewModel.state.value.currentStep)
        viewModel.onSwipeLeftDelete()
        assertEquals(IntroWizardStep.AiEntry, viewModel.state.value.currentStep)
        viewModel.onAiEntryTriggered()
        assertEquals(IntroWizardStep.AiInfo, viewModel.state.value.currentStep)
        viewModel.onAiInfoNext()
        assertEquals(IntroWizardStep.Done, viewModel.state.value.currentStep)
        viewModel.reset()
        assertEquals(IntroWizardStep.AddEntry, viewModel.state.value.currentStep)
    }
}
