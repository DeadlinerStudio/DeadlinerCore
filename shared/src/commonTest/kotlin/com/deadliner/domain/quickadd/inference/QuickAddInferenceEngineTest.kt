package com.deadliner.domain.quickadd.inference

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class QuickAddInferenceEngineTest {
    private fun input() = QuickAddModelInput(listOf(QuickAddTensor("input_ids", longArrayOf(1, 2),
        QuickAddTensorType.INT32, longArrayOf(1, 2))), listOf("logits"))

    @Test fun requiresModelAndAllowsReloadAfterClose() = runBlocking {
        val backend = FakeBackend()
        val engine = QuickAddInferenceEngine(backend)
        val failure = assertFailsWith<QuickAddInferenceException> { engine.infer(input()) }
        assertEquals(QuickAddInferenceError.MODEL_NOT_CONFIGURED, failure.code)
        assertFalse(engine.isLoaded())
        engine.load("fixture")
        assertTrue(engine.isLoaded())
        assertContentEquals(floatArrayOf(0.25f, 0.75f), engine.infer(input()).tensors.single().values)
        engine.close(); engine.close()
        assertFalse(engine.isLoaded())
        engine.load("fixture")
        assertTrue(engine.isLoaded())
    }

    @Test fun failedReplacementPreservesLoadedModel() = runBlocking {
        val engine = QuickAddInferenceEngine(FakeBackend())
        engine.load("fixture")
        val failure = assertFailsWith<QuickAddInferenceException> { engine.load("broken") }
        assertEquals(QuickAddInferenceError.MODEL_LOAD_FAILED, failure.code)
        assertTrue(engine.isLoaded())
        assertEquals("logits", engine.infer(input()).tensors.single().name)
    }

    @Test fun rejectsBadShapesPayloadsAndDuplicateNamesBeforeNativeCall() = runBlocking {
        val backend = FakeBackend()
        val engine = QuickAddInferenceEngine(backend)
        engine.load("fixture")
        val valid = input()
        val cases = listOf(
            valid.copy(tensors = listOf(valid.tensors.single().copy(shape = longArrayOf(-1, 2)))),
            valid.copy(tensors = listOf(valid.tensors.single().copy(shape = longArrayOf(Long.MAX_VALUE, 2)))),
            valid.copy(tensors = listOf(valid.tensors.single().copy(integers = longArrayOf(1)))),
            valid.copy(tensors = listOf(valid.tensors.single().copy(integers = longArrayOf(Long.MAX_VALUE, 2)))),
            valid.copy(tensors = valid.tensors + valid.tensors),
            valid.copy(outputNames = listOf("logits", "logits")),
            valid.copy(tensors = listOf(QuickAddTensor("input_ids", longArrayOf(1), QuickAddTensorType.FLOAT32, floats = floatArrayOf(Float.NaN)))),
        )
        cases.forEach { invalid ->
            assertEquals(QuickAddInferenceError.INVALID_INPUT,
                assertFailsWith<QuickAddInferenceException> { engine.infer(invalid) }.code)
        }
        assertEquals(0, backend.calls)
    }

    @Test fun rejectsMalformedNativeOutput() = runBlocking {
        val backend = FakeBackend().apply { badOutput = true }
        val engine = QuickAddInferenceEngine(backend)
        engine.load("fixture")
        assertEquals(QuickAddInferenceError.INVALID_OUTPUT,
            assertFailsWith<QuickAddInferenceException> { engine.infer(input()) }.code)
    }

    @Test fun cancellationIsNotWrappedAsInferenceFailure() = runBlocking {
        val backend = FakeBackend().apply { cancel = true }
        val engine = QuickAddInferenceEngine(backend)
        engine.load("fixture")
        assertFailsWith<CancellationException> { engine.infer(input()) }
        Unit
    }

    @Test fun ownsSnapshotOfCallerInput() = runBlocking {
        val backend = FakeBackend()
        val engine = QuickAddInferenceEngine(backend)
        engine.load("fixture")
        val request = input()
        engine.infer(request)
        assertNotSame(request.tensors.single().integers, backend.lastInput!!.tensors.single().integers)
        request.tensors.single().integers[0] = 99
        assertEquals(1L, backend.lastInput!!.tensors.single().integers[0])
    }

    @Test fun concurrentInferenceNeverOverlapsNativeSession() = runBlocking {
        val backend = FakeBackend().apply { simulateWork = true }
        val engine = QuickAddInferenceEngine(backend)
        engine.load("fixture")
        (1..12).map { async(Dispatchers.Default) { engine.infer(input()) } }.awaitAll()
        assertEquals(12, backend.calls)
        engine.close()
    }

    private class FakeBackend : QuickAddInferenceBackend {
        override val name = "TEST"
        var calls = 0
        var badOutput = false
        var cancel = false
        var simulateWork = false
        private val nativeGuard = Mutex()
        var lastInput: QuickAddModelInput? = null
        override fun load(modelPath: String) { check(modelPath != "broken") }
        override fun infer(input: QuickAddModelInput): QuickAddModelOutput {
            if (cancel) throw CancellationException("obsolete input")
            check(nativeGuard.tryLock()) { "Concurrent native access" }
            try {
                if (simulateWork) runBlocking { delay(10) }
                calls++
                lastInput = input
                return QuickAddModelOutput(listOf(QuickAddOutputTensor("logits", longArrayOf(1, 2),
                    if (badOutput) floatArrayOf(Float.NaN) else floatArrayOf(0.25f, 0.75f))))
            } finally {
                nativeGuard.unlock()
            }
        }
        override fun close() = Unit
    }
}
