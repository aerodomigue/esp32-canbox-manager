package com.canbox.manager.data.github

import android.util.Log
import com.canbox.manager.domain.model.GitHubRelease
import com.canbox.manager.domain.model.ReleaseAsset
import com.canbox.manager.util.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GitHubConfigFile(
    val name: String,
    val downloadUrl: String,
    val size: Long
)

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

    /**
     * Get list of config files from GitHub repo data folder
     */
    suspend fun getConfigFiles(): Result<List<GitHubConfigFile>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching config files from GitHub...")
            val contents = api.getConfigFiles()
            val files = contents
                .filter { it.type == "file" && it.name.endsWith(".json") }
                .map { GitHubConfigFile(it.name, it.downloadUrl ?: "", it.size) }
            Log.d(TAG, "Found ${files.size} config files")
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching config files", e)
            Result.failure(e)
        }
    }

    /**
     * Download a config file content as string
     */
    suspend fun downloadConfigFile(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading config: $url")
            val response = api.downloadFile(url)
            val content = response.string()
            Log.d(TAG, "Downloaded ${content.length} chars")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading config", e)
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
