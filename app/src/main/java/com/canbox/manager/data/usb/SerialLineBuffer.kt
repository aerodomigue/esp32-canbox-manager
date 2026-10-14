package com.canbox.manager.data.usb

/** Reassembles lines without changing their contents or CRLF/LF terminators. */
internal class SerialLineBuffer {
    private val pending = StringBuilder()

    @Synchronized
    fun append(chunk: String): List<String> {
        pending.append(chunk)
        return buildList {
            var end = pending.indexOf("\n")
            while (end >= 0) {
                add(pending.substring(0, end + 1))
                pending.delete(0, end + 1)
                end = pending.indexOf("\n")
            }
        }
    }

    @Synchronized
    fun clear() { pending.clear() }
}
