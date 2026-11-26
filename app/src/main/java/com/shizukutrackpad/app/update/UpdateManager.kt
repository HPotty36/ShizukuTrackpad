package com.shizukutrackpad.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.shizukutrackpad.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Checks GitHub Releases for a newer APK, downloads it, verifies it and hands it to the
 * system installer (PackageInstaller session; the user confirms in the system dialog).
 *
 * Process-wide singleton so the install-result receiver can report back into the same state.
 */
class UpdateManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = GitHubReleaseClient(BuildConfig.UPDATE_REPO)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updatesDir = File(appContext.cacheDir, "updates")

    /** Debug builds have a ".debug" package and a different key, so they never self-update. */
    val isSupported: Boolean = !BuildConfig.DEBUG && BuildConfig.UPDATE_REPO.isNotBlank()

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(
        if (isSupported) UpdateState.Idle else UpdateState.Unsupported
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dismissedVersion = MutableStateFlow(prefs.getString(KEY_DISMISSED_VERSION, null))

    /** Version whose top-of-screen banner the user closed; the settings sheet still offers it. */
    val dismissedVersion: StateFlow<String?> = _dismissedVersion.asStateFlow()

    @Volatile
    private var job: Job? = null

    // Leftovers from an earlier run (installed or abandoned) are no longer needed.
    private val cleanup: Job = scope.launch { updatesDir.deleteRecursively() }

    /** Silent background check, at most once per [CHECK_INTERVAL_MS]. */
    fun checkIfDue() {
        if (!isSupported) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now >= last && now - last < CHECK_INTERVAL_MS) return
        if (_state.value != UpdateState.Idle) return
        check(silent = true)
    }

    /** User-initiated check from the settings sheet. */
    fun checkNow() = check(silent = false)

    private fun check(silent: Boolean) {
        if (!isSupported || job?.isActive == true) return
        if (!silent) _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val release = client.fetchLatestRelease()
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                when {
                    release == null -> UpdateState.UpToDate(latestVersion = null)
                    VersionComparator.isNewer(release.versionName, currentVersion) ->
                        UpdateState.Available(release)
                    else -> UpdateState.UpToDate(latestVersion = release.versionName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoApkAssetException) {
                if (silent) UpdateState.Idle else UpdateState.Failed(UpdateError.NO_APK, null)
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (silent) UpdateState.Idle else UpdateState.Failed(UpdateError.NETWORK, null, e.message)
            }
        }
    }

    fun dismissBanner(release: ReleaseInfo) {
        prefs.edit().putString(KEY_DISMISSED_VERSION, release.versionName).apply()
        _dismissedVersion.value = release.versionName
    }

    /** Downloads, verifies and installs the release currently offered (or the one that failed). */
    fun downloadAndInstall() {
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Failed -> current.release
            else -> null
        } ?: return
        if (job?.isActive == true) return

        _state.value = UpdateState.Downloading(release, progress = 0f)
        job = scope.launch {
            val apk = File(updatesDir, "update.apk")
            cleanup.join()
            try {
                client.downloadApk(release, apk) { progress ->
                    _state.value = UpdateState.Downloading(release, progress)
                }
                verifyApk(apk)
                _state.value = UpdateState.Installing(release)
                commitInstallSession(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignatureMismatchException) {
                Log.w(TAG, "Update signed with a different key", e)
                apk.delete()
                _state.value = UpdateState.Failed(UpdateError.SIGNATURE_MISMATCH, release)
            } catch (e: ApkVerificationException) {
                Log.w(TAG, "Downloaded APK failed verification", e)
                apk.delete()
                _state.value = UpdateState.Failed(UpdateError.INVALID_APK, release, e.message)
            } catch (e: IOException) {
                Log.w(TAG, "Update download failed", e)
                apk.delete()
                _state.value = UpdateState.Failed(UpdateError.DOWNLOAD, release, e.message)
            } catch (e: Exception) {
                Log.e(TAG, "Update install failed", e)
                apk.delete()
                _state.value = UpdateState.Failed(UpdateError.INSTALL, release, e.message)
            }
        }
    }

    /** Called by [UpdateInstallReceiver] with the PackageInstaller session result. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the system dialog is showing
            PackageInstaller.STATUS_SUCCESS -> {
                updatesDir.deleteRecursively()
                _state.value = UpdateState.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user backed out of the system dialog; keep offering the update.
                _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            }
            else -> {
                Log.w(TAG, "Install failed: status=$status message=$message")
                _state.value = UpdateState.Failed(UpdateError.INSTALL, release, message)
            }
        }
    }

    @Throws(IOException::class)
    private fun verifyApk(apk: File) {
        val pm = appContext.packageManager
        val archive = getArchiveInfo(pm, apk)
            ?: throw ApkVerificationException("Not a valid APK")
        if (archive.packageName != appContext.packageName) {
            throw ApkVerificationException("Unexpected package ${archive.packageName}")
        }
        val installed = getInstalledInfo(pm)
        if (archive.longVersionCode <= installed.longVersionCode) {
            throw ApkVerificationException(
                "versionCode ${archive.longVersionCode} is not newer than ${installed.longVersionCode}"
            )
        }
        val newSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val installedSigning = installed.signingInfo
        val knownSigners: Set<Signature> = when {
            installedSigning == null -> emptySet()
            installedSigning.hasMultipleSigners() -> installedSigning.apkContentsSigners.toSet()
            else -> installedSigning.signingCertificateHistory.toSet()
        }
        // Only decide when both sides are readable; otherwise the system installer still enforces it.
        if (newSigners.isNotEmpty() && knownSigners.isNotEmpty() && newSigners.none { it in knownSigners }) {
            throw SignatureMismatchException()
        }
    }

    @Throws(IOException::class)
    private fun commitInstallSession(apk: File) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(appContext, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(appContext.packageName)
                // Mutable: PackageInstaller fills in the status extras.
                val pending = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun getInstalledInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    companion object {
        private const val TAG = "UpdateManager"
        private const val PREFS_NAME = "app_update"
        private const val KEY_LAST_CHECK = "last_check_millis"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        internal const val ACTION_INSTALL_RESULT = "com.shizukutrackpad.app.action.UPDATE_INSTALL_RESULT"

        @Volatile
        private var instance: UpdateManager? = null

        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context).also { instance = it }
            }
    }
}

private class SignatureMismatchException : IOException("APK signed with a different key")

/** Receives PackageInstaller session results and launches the system confirmation dialog. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure {
                    UpdateManager.get(context).onInstallResult(PackageInstaller.STATUS_FAILURE, it.message)
                }
                return
            }
        }
        UpdateManager.get(context).onInstallResult(status, message)
    }
}
