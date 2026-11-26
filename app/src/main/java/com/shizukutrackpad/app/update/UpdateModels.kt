package com.shizukutrackpad.app.update

/** The newest published GitHub Release that carries an APK asset. */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 from the asset's `digest` field, when GitHub provides one. */
    val sha256: String?
)

enum class UpdateError {
    /** Could not reach GitHub or it answered with an unexpected response. */
    NETWORK,

    /** The latest release has no .apk asset attached. */
    NO_APK,

    /** The APK download failed or was cut short. */
    DOWNLOAD,

    /** The downloaded file failed the checksum / package / version checks. */
    INVALID_APK,

    /** The APK is signed with a different key than the installed app. */
    SIGNATURE_MISMATCH,

    /** The system installer rejected the update. */
    INSTALL
}

sealed interface UpdateState {
    /** Debug builds use a different package name, so they cannot update themselves. */
    data object Unsupported : UpdateState

    data object Idle : UpdateState

    data object Checking : UpdateState

    /** [latestVersion] is null when the repository has no releases yet. */
    data class UpToDate(val latestVersion: String?) : UpdateState

    data class Available(val release: ReleaseInfo) : UpdateState

    /** [progress] is 0..1, or null while the total size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState

    /** Handed to the system installer; waiting for the user to confirm. */
    data class Installing(val release: ReleaseInfo) : UpdateState

    data class Failed(
        val error: UpdateError,
        val release: ReleaseInfo?,
        val detail: String? = null
    ) : UpdateState
}

/** Compares dotted numeric versions ("1.10.0" > "1.9.3"); a leading "v" and any "-suffix"/"+build" are ignored. */
object VersionComparator {

    fun parse(version: String): List<Int>? {
        val core = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }

    /** True when [candidate] is strictly newer than [current]; false if either cannot be parsed. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
