package com.canbox.manager.data.github

import android.util.Log
import com.canbox.manager.domain.model.GitHubRelease
import com.canbox.manager.domain.model.ReleaseAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GitHubRepository(
    private val api: GitHubApi
) {
    companion object {
        private const val TAG = "GitHubRepository"
    }

    /**
     * Check if a newer version of the app is available
     * Returns the latest version tag if update available, null otherwise
     */
    suspend fun checkAppUpdate(currentVersion: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for app updates, current version: $currentVersion")
            val release = api.getAppLatestRelease()
            val latestVersion = release.tagName.removePrefix("v")

            Log.d(TAG, "Latest app version: $latestVersion")

            if (isNewerVersion(latestVersion, currentVersion)) {
                Log.d(TAG, "Update available: $latestVersion > $currentVersion")
                Result.success(release.tagName)
            } else {
                Log.d(TAG, "No update available")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app update", e)
            Result.failure(e)
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    suspend fun getReleases(): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching releases...")
            val dtos = api.getReleases()
            Log.d(TAG, "Got ${dtos.size} releases from API")

            val releases = dtos.map { dto ->
                Log.d(TAG, "Mapping release: ${dto.tagName} - ${dto.name}")
                GitHubRelease(
                    tagName = dto.tagName,
                    name = dto.name,
                    body = dto.body ?: "",
                    publishedAt = dto.publishedAt,
                    prerelease = dto.prerelease,
                    assets = dto.assets.map { asset ->
                        Log.d(TAG, "  Asset: ${asset.name} (${asset.size} bytes)")
                        ReleaseAsset(
                            name = asset.name,
                            downloadUrl = asset.browserDownloadUrl,
                            size = asset.size
                        )
                    }
                )
            }
            Log.d(TAG, "Returning ${releases.size} releases")
            Result.success(releases)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching releases", e)
            Result.failure(e)
        }
    }

    suspend fun downloadFirmware(url: String, targetFile: File, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.downloadFile(url)
                val totalBytes = response.contentLength()
                var downloadedBytes = 0L

                targetFile.outputStream().use { output ->
                    response.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                onProgress(downloadedBytes.toFloat() / totalBytes)
                            }
                        }
                    }
                }
                Result.success(targetFile)
            } catch (e: Exception) {
                targetFile.delete()
                Result.failure(e)
            }
        }
}
