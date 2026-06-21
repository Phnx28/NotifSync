package com.phnx28.notifsync.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateHelper {

    private const val REPO_OWNER = "Phnx28"
    private const val REPO_NAME = "NotifSync"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val APK_NAME = "NotifSync-debug.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class Release(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("body") val body: String,
        @SerializedName("assets") val assets: List<Asset>
    )

    data class Asset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String
    )

    fun checkForUpdate(context: Context, view: View, currentVersion: String) {
        view.showNeutralSnackbar("Checking for updates…")

        Thread {
            try {
                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    postError(view, "Failed to check updates (${response.code})")
                    return@Thread
                }

                val body = response.body?.string() ?: ""
                response.close()
                val release = gson.fromJson(body, Release::class.java)

                val latestVersion = release.tagName.removePrefix("v")
                if (compareVersions(latestVersion, currentVersion) <= 0) {
                    postSuccess(view, "You're up to date (v$currentVersion)")
                    return@Thread
                }

                postInfo(view, "New version available: v$latestVersion")

                val apkAsset = release.assets.firstOrNull { it.name.contains(APK_NAME) || it.name.endsWith(".apk") }
                if (apkAsset == null) {
                    postError(view, "No APK found in release")
                    return@Thread
                }

                downloadAndInstall(context, view, apkAsset.downloadUrl, latestVersion)

            } catch (e: Exception) {
                postError(view, "Update check failed: ${e.message}")
            }
        }.start()
    }

    private fun downloadAndInstall(context: Context, view: View, url: String, version: String) {
        postInfo(view, "Downloading v$version…")

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                postError(view, "Download failed (${response.code})")
                return
            }

            val apkFile = File(context.cacheDir, "update-v$version.apk")
            response.body?.byteStream()?.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            response.close()

            postSuccess(view, "Download complete. Installing…")

            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

        } catch (e: Exception) {
            postError(view, "Install failed: ${e.message}")
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun postSuccess(view: View, msg: String) {
        view.post { view.showSuccessSnackbar(msg) }
    }

    private fun postError(view: View, msg: String) {
        view.post { view.showErrorSnackbar(msg) }
    }

    private fun postInfo(view: View, msg: String) {
        view.post { view.showNeutralSnackbar(msg) }
    }
}
