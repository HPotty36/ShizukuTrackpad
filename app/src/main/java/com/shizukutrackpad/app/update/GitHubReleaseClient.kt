package com.shizukutrackpad.app.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Thrown when the latest release exists but has no APK attached. */
class NoApkAssetException : IOException("Latest release has no .apk asset")

/**
 * Minimal GitHub Releases client on plain HttpURLConnection (no extra dependency).
 * All calls block; run them off the main thread.
 */
class GitHubReleaseClient(private val repo: String) {

    /** Returns the latest published release, or null when the repository has none yet. */
    @Throws(IOException::class)
    fun fetchLatestRelease(): ReleaseInfo? {
        val connection = open(URL("https://api.github.com/repos/$repo/releases/latest")).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                else -> throw IOException("GitHub API returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads [release]'s APK into [target], reporting progress as 0..1 (or null when the
     * size is unknown). Verifies size and, when GitHub published one, the SHA-256 digest.
     */
    @Throws(IOException::class)
    fun downloadApk(release: ReleaseInfo, target: File, onProgress: (Float?) -> Unit) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val connection = open(URL(release.apkUrl)).apply {
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("APK download returned HTTP $code")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastReported = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (total != null) {
                            val percent = ((written * 100) / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                            }
                        } else if (lastReported != 0) {
                            lastReported = 0
                            onProgress(null)
                        }
                    }
                }
            }
            if (release.apkSize > 0 && written != release.apkSize) {
                throw ApkVerificationException("Size mismatch: got $written of ${release.apkSize} bytes")
            }
            val expected = release.sha256
            if (expected != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw ApkVerificationException("SHA-256 mismatch")
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not move downloaded APK into place")
        } catch (t: Throwable) {
            partial.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "ShizukuTrackpad-Updater")
        }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val DEFAULT_BUFFER = 64 * 1024

        internal fun parseRelease(json: JSONObject): ReleaseInfo {
            val tag = json.getString("tag_name")
            val assets = json.optJSONArray("assets")
            var apk: JSONObject? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = asset
                        break
                    }
                }
            }
            if (apk == null) throw NoApkAssetException()
            val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
            return ReleaseInfo(
                tagName = tag,
                versionName = tag.removePrefix("v").removePrefix("V"),
                htmlUrl = json.optString("html_url"),
                apkUrl = apk.getString("browser_download_url"),
                apkName = apk.getString("name"),
                apkSize = apk.optLong("size", 0L),
                sha256 = digest
            )
        }
    }
}

/** The downloaded bytes are not the APK the release advertised. */
class ApkVerificationException(message: String) : IOException(message)
