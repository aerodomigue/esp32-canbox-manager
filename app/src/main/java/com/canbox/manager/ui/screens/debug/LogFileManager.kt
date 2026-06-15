package com.canbox.manager.ui.screens.debug

import com.canbox.manager.domain.model.CanFrame
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

internal class LogFileManager(private val directory: File) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var writer: BufferedWriter? = null
    private var tempFile: File? = null
    private var frameCount = 0L

    fun open() {
        directory.mkdirs()
        val temp = File(directory, "canlog_tmp.txt")
        tempFile = temp
        frameCount = 0L
        writer = BufferedWriter(FileWriter(temp, false))
        writer?.write("# CANBox log\n")
        writer?.write("# Time              CAN_ID  DLC  Data\n")
        writer?.flush()
    }

    fun writeFrame(frame: CanFrame) {
        val w = writer ?: return
        val time = timeFormat.format(Date(frame.timestamp))
        val id = "0x%03X".format(frame.canId)
        val data = frame.data.joinToString(" ") { "%02X".format(it) }
        w.write("$time  $id  [${frame.dlc}]  $data\n")
        frameCount++
        if (frameCount % 100 == 0L) w.flush()
    }

    fun flush() {
        writer?.flush()
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }

    fun save(): File {
        flush()
        val temp = tempFile ?: throw IllegalStateException("No log — call open() first")
        if (!temp.exists() || frameCount == 0L) throw IllegalStateException("No frames to save")
        val finalFile = File(directory, "canlog_${dateFormat.format(Date())}.txt")
        temp.copyTo(finalFile, overwrite = true)
        return finalFile
    }

    fun hasFrames(): Boolean = frameCount > 0L
}
