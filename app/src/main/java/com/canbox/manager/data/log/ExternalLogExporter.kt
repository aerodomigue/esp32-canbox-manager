package com.canbox.manager.data.log

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

class ExternalStoragePermissionRequired : IOException("Autoriser l’accès aux fichiers pour copier sur USB / SD")

class ExternalLogExporter(context: Context) {
    private val storage = context.getSystemService(StorageManager::class.java)

    /** Null means no removable storage; primary/emulated internal storage is never selected. */
    fun export(snapshot: File, checkCancelled: () -> Unit): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IOException("L’export USB / SD automatique nécessite Android 11 ou plus")
        }
        val removable = storage.storageVolumes.filter {
            it.isRemovable && !it.isPrimary && !it.isEmulated
        }
        val candidates = removable.filter {
            it.state == Environment.MEDIA_MOUNTED && it.directory != null
        }
        if (candidates.isEmpty()) {
            if (removable.any { it.state == Environment.MEDIA_MOUNTED_READ_ONLY }) {
                throw IOException("Stockage externe en lecture seule ; copie locale conservée")
            }
            return null
        }
        if (!Environment.isExternalStorageManager()) throw ExternalStoragePermissionRequired()

        // Stable selection when both a card and a drive are connected; no folder picker.
        val volume = candidates.sortedWith(compareBy({ it.uuid.orEmpty() }, { it.directory?.path })).first()
        val root = volume.directory ?: throw IOException("Stockage externe indisponible")
        return LogFileCopy.copy(snapshot, File(root, "CANBox/logs")) {
            checkCancelled()
            val stillMounted = storage.storageVolumes.any {
                it.uuid == volume.uuid && it.directory == root && it.state == Environment.MEDIA_MOUNTED
            }
            if (!stillMounted || !root.isDirectory) {
                throw IOException("Stockage externe retiré pendant l’export ; copie locale conservée")
            }
        }
    }
}
