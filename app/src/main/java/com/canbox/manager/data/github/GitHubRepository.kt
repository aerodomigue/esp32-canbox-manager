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
