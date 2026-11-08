package com.canbox.manager.ui.screens.calibration

import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CalibrationConfig
import com.canbox.manager.domain.repository.CalibrationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<CalibrationViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val baseline = CalibrationConfig(-125, 425, true, 750, 9, 60, 320)
    private val desired = CalibrationConfig(150, 600, false, 900, 11, 70, 400)
    private val vehicle = CalibrationConfig(-80, 450, false, 650, 8, 55, 300)
    private val factory = CalibrationConfig(0, 300, false, 500, 7, 45, 283)
    private val setCommands = listOf(
        "SET steerOffset=150", "SET steerScale=600", "SET steerInvert=0",
        "SET indTimeout=900", "SET rpmDiv=11", "SET tankCap=70", "SET dteDiv=400"
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        try {
            viewModels.forEach { it.viewModelScope.cancel() }
            // Release even deliberately non-cancellable fake replies if an assertion fails.
            gates.forEach { it.complete(Unit) }
            dispatcher.scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `disconnected state has no default configuration and ignores all actions`() = runTest(dispatcher) {
        val (repository, viewModel) = create(UsbConnectionState.Disconnected)
        runCurrent()
        editAll(viewModel)
        invokeAllOperations(viewModel)
        runCurrent()

        assertEquals(CalibrationUiState(), viewModel.uiState.value)
        assertTrue(repository.calls.isEmpty())
        assertEquals(0, repository.operationCount)
    }

    @Test fun `connecting and connection errors do not expose defaults or issue commands`() = runTest(dispatcher) {
        val (repository, viewModel) = create(UsbConnectionState.Connecting)
        runCurrent()
        invokeAllOperations(viewModel)
        runCurrent()
        assertEquals(CalibrationUiState(), viewModel.uiState.value)

        repository.connectionState.value = UsbConnectionState.Error("USB unavailable")
        runCurrent()
        invokeAllOperations(viewModel)
        runCurrent()
        assertEquals(CalibrationUiState(), viewModel.uiState.value)
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun `initial load exposes no config until a verified reply and blocks edits and actions`() = runTest(dispatcher) {
        val (repository, viewModel) = create()
        val reply = gate()
        repository.reads.addLast { reply.await(); Result.success(baseline) }
        assertNull(viewModel.uiState.value.config)
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.config)

        editAll(viewModel)
        invokeAllOperations(viewModel)
        runCurrent()
        assertCalls(repository, "LIST")
        assertNull(viewModel.uiState.value.config)

        reply.complete(Unit)
        runCurrent()
        assertEquals(CalibrationUiState(config = baseline), viewModel.uiState.value)
    }

    @Test fun `load failure clears config and blocks saving until a successful fresh load`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        repository.reads.addLast { Result.failure(IllegalArgumentException("Incomplete CFG LIST")) }
        viewModel.loadConfig()
        runCurrent()
        assertFailed(viewModel, "Incomplete CFG LIST")

        editAll(viewModel)
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "LIST")
        repository.config = vehicle
        viewModel.loadConfig()
        runCurrent()
        assertEquals(CalibrationUiState(config = vehicle), viewModel.uiState.value)
    }

    @Test fun `restore sends RELOAD then LIST without RESET and replaces edited baseline`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        viewModel.restoreVehicleValues()
        runCurrent()

        assertCalls(repository, "RELOAD", "LIST")
        assertSucceeded(viewModel, vehicle)
        viewModel.updateSteeringOffset(vehicle.steeringOffset + 1)
        assertTrue(viewModel.uiState.value.hasChanges)
        viewModel.updateSteeringOffset(vehicle.steeringOffset)
        assertFalse(viewModel.uiState.value.hasChanges)
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "RELOAD", "LIST")
    }

    @Test fun `factory reset sends RESET then LIST without RELOAD and adopts readback`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        viewModel.resetToFactoryDefaults()
        runCurrent()

        assertCalls(repository, "RESET", "LIST")
        assertSucceeded(viewModel, factory)
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "RESET", "LIST")
    }

    @Test fun `restore and factory success remain pending until readback completes`() = runTest(dispatcher) {
        for (restore in listOf(true, false)) {
            val (repository, viewModel) = loaded()
            val reply = gate()
            repository.reads.addLast { reply.await(); Result.success(vehicle) }
            if (restore) viewModel.restoreVehicleValues() else viewModel.resetToFactoryDefaults()
            runCurrent()
            assertTrue(viewModel.uiState.value.isSaving)
            assertNull(viewModel.uiState.value.successMessage)
            assertCalls(repository, if (restore) "RELOAD" else "RESET", "LIST")

            reply.complete(Unit)
            runCurrent()
            // The UI must reflect LIST, even when it differs from assumed factory values.
            assertSucceeded(viewModel, vehicle)
        }
    }

    @Test fun `restore and factory readback failures never report success`() = runTest(dispatcher) {
        for (restore in listOf(true, false)) {
            val (repository, viewModel) = loaded()
            repository.reads.addLast { Result.failure(IllegalStateException("readback failed")) }
            if (restore) viewModel.restoreVehicleValues() else viewModel.resetToFactoryDefaults()
            runCurrent()
            assertCalls(repository, if (restore) "RELOAD" else "RESET", "LIST")
            assertFailed(viewModel, "readback failed")
        }
    }

    @Test fun `restore and factory command failures stop before LIST`() = runTest(dispatcher) {
        for (restore in listOf(true, false)) {
            val (repository, viewModel) = loaded()
            val command = if (restore) "RELOAD" else "RESET"
            repository.beforeCommand = { Result.failure(IllegalStateException("command rejected")) }
            if (restore) viewModel.restoreVehicleValues() else viewModel.resetToFactoryDefaults()
            runCurrent()
            assertCalls(repository, command)
            assertFailed(viewModel, "command rejected")
        }
    }

    @Test fun `SAVE without changes or after reverting edits issues no commands`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        viewModel.saveToNvs()
        viewModel.updateSteeringOffset(desired.steeringOffset)
        assertTrue(viewModel.uiState.value.hasChanges)
        viewModel.updateSteeringOffset(baseline.steeringOffset)
        assertFalse(viewModel.uiState.value.hasChanges)
        viewModel.saveToNvs()
        runCurrent()
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun `SAVE orders preflight SETs verification SAVE and final readback in one operation`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        val operationsBefore = repository.operationCount
        editAll(viewModel)
        viewModel.saveToNvs()
        assertTrue(viewModel.uiState.value.isSaving)
        runCurrent()

        assertCalls(repository, *(listOf("LIST") + setCommands + listOf("LIST", "SAVE", "LIST")).toTypedArray())
        assertEquals(operationsBefore + 1, repository.operationCount)
        assertSucceeded(viewModel, desired)
        repository.calls.clear()
        viewModel.saveToNvs()
        runCurrent()
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun `SAVE writes only changed parameters and uses boolean one`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        repository.config = baseline.copy(steeringInvert = false)
        viewModel.loadConfig()
        runCurrent()
        repository.calls.clear()
        viewModel.updateSteeringInvert(true)
        viewModel.saveToNvs()
        runCurrent()

        assertCalls(repository, "LIST", "SET steerInvert=1", "LIST", "SAVE", "LIST")
        assertSucceeded(viewModel, baseline)
    }

    @Test fun `SAVE preflight failure prevents every write`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        repository.reads.addLast { Result.failure(IllegalStateException("preflight failed")) }
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "LIST")
        assertFailed(viewModel, "preflight failed")
    }

    @Test fun `profile changed since baseline prevents SAVE until a fresh read`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        repository.config = vehicle
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "LIST")
        assertFailed(viewModel, "changed on the ESP")
        assertEquals(vehicle, repository.config)

        viewModel.loadConfig()
        runCurrent()
        repository.calls.clear()
        viewModel.updateSteeringOffset(desired.steeringOffset)
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "LIST", "SET steerOffset=150", "LIST", "SAVE", "LIST")
        assertSucceeded(viewModel, vehicle.copy(steeringOffset = desired.steeringOffset))
    }

    @Test fun `each failed SET stops remaining SETs verification and SAVE`() = runTest(dispatcher) {
        for ((index, failingCommand) in setCommands.withIndex()) {
            val (repository, viewModel) = loaded()
            editAll(viewModel)
            repository.beforeCommand = { call ->
                if (call.command == failingCommand) Result.failure(IllegalStateException("SET failed"))
                else Result.success(Unit)
            }
            viewModel.saveToNvs()
            runCurrent()
            assertCalls(repository, *(listOf("LIST") + setCommands.take(index + 1)).toTypedArray())
            assertFailed(viewModel, "SET failed")
            editAll(viewModel)
            viewModel.saveToNvs()
            runCurrent()
            assertEquals(index + 2, repository.calls.size)
        }
    }

    @Test fun `pre SAVE readback failure or mismatch prevents persistence`() = runTest(dispatcher) {
        for (reply in listOf(Result.failure<CalibrationConfig>(IllegalStateException("readback failed")), Result.success(baseline))) {
            val (repository, viewModel) = loaded()
            editAll(viewModel)
            repository.reads.addLast { Result.success(baseline) }
            repository.reads.addLast { reply }
            viewModel.saveToNvs()
            runCurrent()
            assertCalls(repository, *(listOf("LIST") + setCommands + "LIST").toTypedArray())
            assertFailed(viewModel)
        }
    }

    @Test fun `SAVE command failure stops final readback and reports no success`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        repository.beforeCommand = { call ->
            if (call.command == "SAVE") Result.failure(IllegalStateException("NVS write failed"))
            else Result.success(Unit)
        }
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, *(listOf("LIST") + setCommands + listOf("LIST", "SAVE")).toTypedArray())
        assertFailed(viewModel, "NVS write failed")
    }

    @Test fun `post SAVE readback failure or mismatch never reports success`() = runTest(dispatcher) {
        for (reply in listOf(Result.failure<CalibrationConfig>(IllegalStateException("readback failed")), Result.success(baseline))) {
            val (repository, viewModel) = loaded()
            editAll(viewModel)
            repository.reads.addLast { Result.success(baseline) }
            repository.reads.addLast { Result.success(desired) }
            repository.reads.addLast { reply }
            viewModel.saveToNvs()
            runCurrent()
            assertCalls(repository, *(listOf("LIST") + setCommands + listOf("LIST", "SAVE", "LIST")).toTypedArray())
            assertFailed(viewModel)
        }
    }

    @Test fun `double clicks other operations and every editor are blocked throughout SAVE`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        val preflight = gate()
        val finalReply = gate()
        repository.reads.addLast { preflight.await(); Result.success(baseline) }
        repository.reads.addLast { Result.success(desired) }
        repository.reads.addLast { finalReply.await(); Result.success(desired) }
        editAll(viewModel)
        viewModel.saveToNvs()
        invokeAllOperations(viewModel)
        runCurrent()
        assertCalls(repository, "LIST")
        editAll(viewModel, baseline)
        invokeAllOperations(viewModel)
        runCurrent()
        assertEquals(desired, viewModel.uiState.value.config)
        assertCalls(repository, "LIST")

        preflight.complete(Unit)
        runCurrent()
        assertTrue(viewModel.uiState.value.isSaving)
        assertNull(viewModel.uiState.value.successMessage)
        editAll(viewModel, baseline)
        invokeAllOperations(viewModel)
        runCurrent()
        assertEquals(desired, viewModel.uiState.value.config)
        assertCalls(repository, *(listOf("LIST") + setCommands + listOf("LIST", "SAVE", "LIST")).toTypedArray())

        finalReply.complete(Unit)
        runCurrent()
        assertSucceeded(viewModel, desired)
    }

    @Test fun `restore and reset double clicks block competing actions and edits`() = runTest(dispatcher) {
        for (restore in listOf(true, false)) {
            val (repository, viewModel) = loaded()
            val reply = gate()
            repository.beforeCommand = { reply.await(); Result.success(Unit) }
            if (restore) viewModel.restoreVehicleValues() else viewModel.resetToFactoryDefaults()
            invokeAllOperations(viewModel)
            runCurrent()
            editAll(viewModel)
            invokeAllOperations(viewModel)
            runCurrent()
            assertEquals(baseline, viewModel.uiState.value.config)
            assertTrue(viewModel.uiState.value.isSaving)
            assertCalls(repository, if (restore) "RELOAD" else "RESET")
            reply.complete(Unit)
            runCurrent()
            assertCalls(repository, if (restore) "RELOAD" else "RESET", "LIST")
            assertSucceeded(viewModel, if (restore) vehicle else factory)
        }
    }

    @Test fun `disconnect clears edits and reconnect loads a new baseline`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        repository.connectionState.value = UsbConnectionState.Disconnected
        runCurrent()
        assertEquals(CalibrationUiState(), viewModel.uiState.value)
        invokeAllOperations(viewModel)
        editAll(viewModel)
        runCurrent()
        assertTrue(repository.calls.isEmpty())

        repository.config = vehicle
        repository.connectionState.value = connected(2)
        runCurrent()
        assertCalls(repository, "LIST", session = 2)
        assertEquals(CalibrationUiState(config = vehicle), viewModel.uiState.value)
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "LIST", session = 2)
    }

    @Test fun `new session reloads even when disconnected emission is conflated`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        editAll(viewModel)
        repository.config = vehicle
        repository.connectionState.value = UsbConnectionState.Disconnected
        repository.connectionState.value = connected(2)
        runCurrent()
        assertCalls(repository, "LIST", session = 2)
        assertEquals(CalibrationUiState(config = vehicle), viewModel.uiState.value)
    }

    @Test fun `late successful reply from old session never becomes the new baseline`() = runTest(dispatcher) {
        assertStaleReplyIgnored(Result.success(desired))
    }

    @Test fun `late failed reply from old session does not contaminate reconnect state`() = runTest(dispatcher) {
        assertStaleReplyIgnored(Result.failure(IllegalStateException("old session failed")))
    }

    @Test fun `reconnect during SET prevents remaining writes and SAVE from old session`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        val reply = gate()
        var oldReplyReturned = false
        repository.beforeCommand = { call ->
            if (call.command == setCommands.first()) {
                withContext(NonCancellable) { reply.await() }
                oldReplyReturned = true
                Result.failure(IllegalStateException("old SET reply"))
            } else Result.success(Unit)
        }
        editAll(viewModel)
        viewModel.saveToNvs()
        runCurrent()
        assertCalls(repository, "LIST", setCommands.first())
        repository.config = vehicle
        repository.connectionState.value = connected(2)
        runCurrent()
        assertNull(viewModel.uiState.value.config)
        reply.complete(Unit)
        runCurrent()
        assertTrue(oldReplyReturned)
        assertEquals(listOf(Call("LIST", 1), Call(setCommands.first(), 1), Call("LIST", 2)), repository.calls)
        assertEquals(CalibrationUiState(config = vehicle), viewModel.uiState.value)
    }

    @Test fun `operations waiting for mutex cannot write after session changes`() = runTest(dispatcher) {
        for (action in listOf("SAVE", "RELOAD", "RESET")) {
            val (repository, viewModel) = loaded()
            editAll(viewModel)
            repository.operationMutex.lock()
            try {
                when (action) {
                    "SAVE" -> viewModel.saveToNvs()
                    "RELOAD" -> viewModel.restoreVehicleValues()
                    else -> viewModel.resetToFactoryDefaults()
                }
                runCurrent()
                assertTrue(viewModel.uiState.value.isSaving)
                assertTrue(repository.calls.isEmpty())
                repository.config = vehicle
                repository.connectionState.value = connected(2)
                runCurrent()
                assertNull(viewModel.uiState.value.config)
                assertTrue(viewModel.uiState.value.isLoading)
            } finally {
                repository.operationMutex.unlock()
            }
            runCurrent()
            assertCalls(repository, "LIST", session = 2)
            assertEquals(CalibrationUiState(config = vehicle), viewModel.uiState.value)
        }
    }

    @Test fun `operation lock remains held through final SAVE readback`() = runTest(dispatcher) {
        val (repository, viewModel) = loaded()
        val reply = gate()
        repository.reads.addLast { Result.success(baseline) }
        repository.reads.addLast { Result.success(desired) }
        repository.reads.addLast { reply.await(); Result.success(desired) }
        editAll(viewModel)
        viewModel.saveToNvs()
        runCurrent()
        var profileLoaded = false
        val profileLoad = launch {
            repository.withCalibrationOperation {
                repository.config = vehicle
                profileLoaded = true
            }
        }
        runCurrent()
        assertFalse(profileLoaded)
        assertNull(viewModel.uiState.value.successMessage)
        reply.complete(Unit)
        runCurrent()
        profileLoad.join()
        assertSucceeded(viewModel, desired)
        assertTrue(profileLoaded)
        assertEquals(vehicle, repository.config)
    }

    private fun create(state: UsbConnectionState = connected(1)): Pair<FakeRepository, CalibrationViewModel> {
        val repository = FakeRepository(state)
        val viewModel = CalibrationViewModel(repository)
        viewModels += viewModel
        return repository to viewModel
    }

    private fun loaded(): Pair<FakeRepository, CalibrationViewModel> {
        val fixture = create()
        dispatcher.scheduler.runCurrent()
        assertEquals(CalibrationUiState(config = baseline), fixture.second.uiState.value)
        assertCalls(fixture.first, "LIST")
        fixture.first.calls.clear()
        return fixture
    }

    private fun connected(session: Long) = UsbConnectionState.Connected("ESP", 1, 2, session)

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }

    private fun editAll(viewModel: CalibrationViewModel, config: CalibrationConfig = desired) {
        viewModel.updateSteeringOffset(config.steeringOffset)
        viewModel.updateSteeringScale(config.steeringScale)
        viewModel.updateSteeringInvert(config.steeringInvert)
        viewModel.updateIndicatorTimeout(config.indicatorTimeout)
        viewModel.updateRpmDivisor(config.rpmDivisor)
        viewModel.updateTankCapacity(config.tankCapacity)
        viewModel.updateDteDivisor(config.dteDivisor)
    }

    private fun invokeAllOperations(viewModel: CalibrationViewModel) {
        viewModel.loadConfig()
        viewModel.saveToNvs()
        viewModel.restoreVehicleValues()
        viewModel.resetToFactoryDefaults()
    }

    private fun assertCalls(repository: FakeRepository, vararg commands: String, session: Long = 1) {
        assertEquals(commands.map { Call(it, session) }, repository.calls)
    }

    private fun assertFailed(viewModel: CalibrationViewModel, message: String? = null) {
        val state = viewModel.uiState.value
        assertFalse(state.isBusy)
        assertNull(state.config)
        assertFalse(state.hasChanges)
        assertNull(state.successMessage)
        assertTrue("Expected an explicit error: $state", !state.error.isNullOrBlank())
        if (message != null) assertTrue(state.error.orEmpty(), state.error.orEmpty().contains(message))
    }

    private fun assertSucceeded(viewModel: CalibrationViewModel, config: CalibrationConfig) {
        val state = viewModel.uiState.value
        assertEquals(config, state.config)
        assertFalse(state.isBusy)
        assertFalse(state.hasChanges)
        assertNull(state.error)
        assertTrue("Expected success after verified readback", !state.successMessage.isNullOrBlank())
    }

    private fun assertStaleReplyIgnored(oldResult: Result<CalibrationConfig>) {
        val (repository, viewModel) = create()
        val oldReply = gate()
        val newReply = gate()
        var oldReplyReturned = false
        repository.reads.addLast {
            // Model a transport that can deliver a reply despite cancellation.
            withContext(NonCancellable) { oldReply.await() }
            oldReplyReturned = true
            oldResult
        }
        repository.reads.addLast { newReply.await(); Result.success(vehicle) }
        dispatcher.scheduler.runCurrent()
        assertCalls(repository, "LIST")
        repository.connectionState.value = connected(2)
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.config)
        oldReply.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertTrue(oldReplyReturned)
        assertEquals(listOf(Call("LIST", 1), Call("LIST", 2)), repository.calls)
        assertEquals(CalibrationUiState(isLoading = true), viewModel.uiState.value)
        newReply.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(CalibrationUiState(config = vehicle), viewModel.uiState.value)
    }

    private data class Call(val command: String, val sessionId: Long)

    private inner class FakeRepository(initialState: UsbConnectionState) : CalibrationRepository {
        override val connectionState = MutableStateFlow(initialState)
        val operationMutex = Mutex()
        var operationCount = 0
        val calls = mutableListOf<Call>()
        val reads = ArrayDeque<suspend () -> Result<CalibrationConfig>>()
        var config = baseline
        var beforeCommand: suspend (Call) -> Result<Unit> = { Result.success(Unit) }

        override suspend fun <T> withCalibrationOperation(action: suspend () -> T): T =
            operationMutex.withLock {
                operationCount++
                action()
            }

        private suspend fun command(name: String, session: Long): Result<Unit> {
            check(operationMutex.isLocked) { "Calibration command outside operation lock" }
            val call = Call(name, session)
            calls += call
            return beforeCommand(call)
        }

        override suspend fun getCalibration(sessionId: Long): Result<CalibrationConfig> {
            val result = command("LIST", sessionId)
            result.exceptionOrNull()?.let { return Result.failure(it) }
            return if (reads.isEmpty()) Result.success(config) else reads.removeFirst().invoke()
        }

        override suspend fun setCalibration(param: String, value: Int, sessionId: Long): Result<Unit> {
            val result = command("SET $param=$value", sessionId)
            if (result.isSuccess) {
                config = when (param) {
                    "steerOffset" -> config.copy(steeringOffset = value)
                    "steerScale" -> config.copy(steeringScale = value)
                    "steerInvert" -> config.copy(steeringInvert = value == 1)
                    "indTimeout" -> config.copy(indicatorTimeout = value)
                    "rpmDiv" -> config.copy(rpmDivisor = value)
                    "tankCap" -> config.copy(tankCapacity = value)
                    "dteDiv" -> config.copy(dteDivisor = value)
                    else -> error("Unexpected parameter: $param")
                }
            }
            return result
        }

        override suspend fun saveCalibration(sessionId: Long): Result<Unit> = command("SAVE", sessionId)

        override suspend fun resetCalibration(sessionId: Long): Result<Unit> {
            val result = command("RESET", sessionId)
            if (result.isSuccess) config = factory
            return result
        }

        override suspend fun reloadVehicleCalibration(sessionId: Long): Result<Unit> {
            val result = command("RELOAD", sessionId)
            if (result.isSuccess) config = vehicle
            return result
        }
    }
}
