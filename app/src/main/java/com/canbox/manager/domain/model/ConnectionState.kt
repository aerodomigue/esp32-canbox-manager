package com.canbox.manager.domain.model

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val firmwareVersion: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val isConnected: Boolean
        get() = this is Connected

    val statusText: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting..."
            is Connected -> "Connected"
            is Error -> "Error: $message"
        }
}
