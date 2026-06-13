package com.canbox.manager.util

internal fun isNewerVersion(latest: String, current: String): Boolean {
    fun parse(v: String) = v.substringBefore("-").substringBefore("+")
        .split(".").map { it.toIntOrNull() ?: 0 }

    val latestParts = parse(latest)
    val currentParts = parse(current)

    for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
        val l = latestParts.getOrElse(i) { 0 }
        val c = currentParts.getOrElse(i) { 0 }
        if (l > c) return true
        if (l < c) return false
    }
    return false
}
