package com.canbox.manager.data.github

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GitHubApi {
    @GET("repos/aerodomigue/esp32-canbox-nissan/releases")
    suspend fun getReleases(): List<GitHubReleaseDto>

    @GET("repos/aerodomigue/esp32-canbox-manager/releases/latest")
    suspend fun getAppLatestRelease(): GitHubReleaseDto

    @GET("repos/aerodomigue/esp32-canbox-nissan/contents/data")
    suspend fun getConfigFiles(): List<GitHubContentDto>

    @Streaming
    @GET
    suspend fun downloadFile(@Url url: String): okhttp3.ResponseBody
}

data class GitHubContentDto(
    val name: String,
    val path: String,
    val size: Long,
    @SerializedName("download_url")
    val downloadUrl: String?,
    val type: String // "file" or "dir"
)

data class GitHubReleaseDto(
    @SerializedName("tag_name")
    val tagName: String,
    val name: String,
    val body: String?,
    @SerializedName("published_at")
    val publishedAt: String,
    val prerelease: Boolean,
    val assets: List<GitHubAssetDto>
)

data class GitHubAssetDto(
    val name: String,
    @SerializedName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long
)
