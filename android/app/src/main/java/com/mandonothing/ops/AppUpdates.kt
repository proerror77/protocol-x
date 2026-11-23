package com.mandonothing.ops

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

private const val releases = "https://github.com/proerror77/protocol-x/releases"
private const val manifestUrl = "$releases/latest/download/protocol-x-android.json"
private const val maxApkBytes = 512L * 1024 * 1024
private val releaseVersion = Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")
private val sha256Hex = Regex("[a-f0-9]{64}")
private val releaseHosts = setOf(
    "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

internal data class AppRelease(val version: String, val code: Long, val bytes: Long, val sha256: String) {
    init {
        require(releaseVersion.matches(version))
        require(code in 1..Int.MAX_VALUE.toLong() && bytes in 1..maxApkBytes && sha256Hex.matches(sha256))
    }

    val apkName get() = "Protocol_X_v$version.apk"
    val downloadUrl get() = "$releases/download/v$version/$apkName"
    fun isNewerThan(installedCode: Long) = code > installedCode
}

internal class AppUpdates(private val context: Context) {
    private val packageManager get() = context.packageManager

    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = openReleaseUrl(manifestUrl)
        try {
            if (request.responseCode == 404) return@withContext null
            request.requireSuccess()
            val payload = ByteArrayOutputStream()
            request.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(payload.size() + count <= 32 * 1024)
                    payload.write(buffer, 0, count)
                }
            }
            val manifest = JSONObject(payload.toString(Charsets.UTF_8.name()))
            AppRelease(manifest.getString("version_name"), manifest.getLong("version_code"),
                manifest.getLong("bytes"), manifest.getString("sha256"))
        } finally {
            request.disconnect()
        }
    }

    suspend fun download(release: AppRelease, progress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
        require(release.isNewerThan(BuildConfig.VERSION_CODE.toLong()))
        val directory = File(context.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs())
        val apk = File(directory, release.apkName)
        if (apk.isFile && apk.length() == release.bytes && sha256(apk) == release.sha256) {
            verifyPackage(apk, release)
            return@withContext apk
        }
        val pending = File.createTempFile("update-", ".apk", directory)
        try {
            val request = openReleaseUrl(release.downloadUrl)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var lastProgress = -5
            try {
                request.requireSuccess()
                request.inputStream.use { input ->
                    pending.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= release.bytes)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = (size * 100 / release.bytes).toInt()
                            if (percent >= lastProgress + 5 || percent == 100) {
                                lastProgress = percent
                                progress(percent)
                            }
                        }
                    }
                }
            } finally {
                request.disconnect()
            }
            require(size == release.bytes && digest.digest().hex() == release.sha256)
            verifyPackage(pending, release)
            check(pending.renameTo(apk))
            directory.listFiles()?.filter { it.extension == "apk" && it != apk }?.forEach { it.delete() }
            apk
        } finally {
            pending.delete()
        }
    }

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".capture", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun canInstall() = packageManager.canRequestPackageInstalls()

    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, release: AppRelease) {
        val modern = Build.VERSION.SDK_INT >= 28
        val flags = if (modern) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = requireNotNull(packageManager.getPackageArchiveInfo(apk.path, flags))
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        val code = if (modern) candidate.longVersionCode else candidate.versionCode.toLong()
        val candidateSigners = if (modern) candidate.signingInfo?.apkContentsSigners else candidate.signatures
        val installedSigners = if (modern) installed.signingInfo?.apkContentsSigners else installed.signatures
        require(candidate.packageName == context.packageName && candidate.versionName == release.version && code == release.code)
        require(!candidateSigners.isNullOrEmpty() && candidateSigners.toSet() == installedSigners?.toSet())
    }

    private fun openReleaseUrl(url: String): HttpURLConnection {
        var uri = URI(url)
        repeat(6) {
            require(uri.scheme == "https" && uri.userInfo == null && uri.host in releaseHosts &&
                (uri.port == -1 || uri.port == 443))
            val request = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", "Protocol-X-Android")
            }
            try {
                if (request.responseCode !in setOf(301, 302, 303, 307, 308)) return request
                uri = uri.resolve(requireNotNull(request.getHeaderField("Location")))
            } catch (error: Exception) {
                request.disconnect()
                throw error
            }
            request.disconnect()
        }
        throw IOException("Update redirect limit")
    }

    private fun HttpURLConnection.requireSuccess() {
        if (responseCode != 200) throw IOException("Update HTTP $responseCode")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
