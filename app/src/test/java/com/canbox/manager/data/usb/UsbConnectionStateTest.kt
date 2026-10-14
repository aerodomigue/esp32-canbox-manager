package com.canbox.manager.data.usb

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals

class UsbConnectionStateTest {
    @Test fun `new serial session remains visible when disconnect is conflated`() = runBlocking {
        val first = UsbConnectionState.Connected("ESP", 1, 2, sessionId = 1)
        val second = first.copy(sessionId = 2)
        val state = MutableStateFlow<UsbConnectionState>(first)
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            state.take(2).toList()
        }
        state.value = UsbConnectionState.Disconnected
        state.value = second
        assertEquals(listOf(first, second), withTimeout(1000) { observed.await() })
    }
}
