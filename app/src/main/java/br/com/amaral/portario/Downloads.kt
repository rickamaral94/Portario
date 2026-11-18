package br.com.amaral.portario

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun PackageManager.visiblePackage(id: String): PackageInfo? = try {
    if (id.isBlank()) null else getPackageInfo(id, signatureFlag())
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun signatureFlag() =
    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

fun PackageInfo.code(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signers() = if (Build.VERSION.SDK_INT >= 28)
    signingInfo?.apkContentsSigners?.toSet().orEmpty() else signatures?.toSet().orEmpty()

@Suppress("DEPRECATION")
private fun PackageInfo.signingHistory() = if (Build.VERSION.SDK_INT >= 28)
    signingInfo?.signingCertificateHistory?.toSet().orEmpty() else signatures?.toSet().orEmpty()

private fun PackageInfo.canUpdate(installed: PackageInfo): Boolean {
    val prior = installed.signers()
    val candidate = signers()
    check(prior.isNotEmpty() && candidate.isNotEmpty())
    return prior == candidate || (prior.size == 1 && candidate.size == 1 && prior.single() in signingHistory())
}

private class ApkProblem(val label: Int) : IOException()

data class DownloadProgress(val bytes: Long, val total: Long)
data class PendingApproval(val session: Int, val intent: Intent)

class PortarioDownloads(private val app: PortarioApp) {
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val change = Mutex()
    val approval = MutableStateFlow<PendingApproval?>(null)

    suspend fun enqueue(variant: Variant, release: Release, apk: Asset) = withContext(Dispatchers.IO) {
        change.withLock {
            val target = URI(apk.url)
            require(target.scheme == "https" && target.host.equals("github.com", true) &&
                target.userInfo == null && target.port == -1 &&
                target.path.startsWith("/${variant.repository}/releases/download/", ignoreCase = true) &&
                target.path.endsWith(".apk", ignoreCase = true))
            val track = app.dao.track(variant.id)
            app.dao.transfer(variant.id)?.let { manager.remove(it.id); app.dao.deleteTransfer(variant.id, it.id) }
            val request = DownloadManager.Request(Uri.parse(apk.url))
                .setTitle(apk.name).setDescription(variant.name)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(app, "apks", "${UUID.randomUUID()}.apk")
            val id = manager.enqueue(request)
            try {
                app.dao.save(Transfer(variant.id, id, release.tag, release.publishedAt, apk.name, apk.bytes, apk.digest,
                    track?.packageId?.ifBlank { variant.packageId } ?: variant.packageId, apk.id, release.fingerprint()))
            } catch (error: Exception) {
                manager.remove(id)
                throw error
            }
        }
    }

    suspend fun cancel(transfer: Transfer) = withContext(Dispatchers.IO) {
        change.withLock {
            if (app.dao.transfer(transfer.variant)?.id != transfer.id) return@withLock
            manager.remove(transfer.id)
            app.dao.deleteTransfer(transfer.variant, transfer.id)
        }
    }

    suspend fun progress(transfer: Transfer): DownloadProgress = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(transfer.id)).use { c ->
            if (c?.moveToFirst() != true) {
                app.dao.finishDownload(transfer.variant, transfer.id, "error", app.getString(R.string.download_failed))
                return@use DownloadProgress(0, transfer.bytes)
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (transfer.status == "downloading") setStatus(transfer, status)
            DownloadProgress(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: transfer.bytes)
        }
    }

    suspend fun onDownloadComplete(downloadId: Long) {
        val transfer = app.dao.transfer(downloadId) ?: return
        if (transfer.status == "downloading") progress(transfer)
    }

    private suspend fun setStatus(transfer: Transfer, downloadStatus: Int) {
        when (downloadStatus) {
            DownloadManager.STATUS_SUCCESSFUL -> app.dao.finishDownload(transfer.variant, transfer.id, "ready", "")
            DownloadManager.STATUS_FAILED ->
                app.dao.finishDownload(transfer.variant, transfer.id, "error", app.getString(R.string.download_failed))
        }
    }

    suspend fun install(transfer: Transfer) = withContext(Dispatchers.IO) {
        change.withLock {
            val current = app.dao.transfer(transfer.variant)
            if (current?.id != transfer.id || current.status != "ready") return@withLock
            var sessionId: Int? = null
            val installer = app.packageManager.packageInstaller
            try {
                val file = manager.query(DownloadManager.Query().setFilterById(transfer.id)).use { c ->
                    check(c?.moveToFirst() == true)
                    check(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL)
                    val uri = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                    check(uri.scheme == "file")
                    File(requireNotNull(uri.path)).canonicalFile.also {
                        val root = requireNotNull(app.getExternalFilesDir("apks")).canonicalFile
                        check(it.parentFile == root && it.isFile)
                    }
                }
                val candidate = validate(file, transfer)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(candidate.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                val id = installer.createSession(params)
                sessionId = id
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    val intent = Intent(app, InstallReceiver::class.java)
                        .setData(Uri.parse("portario-install://session/$id"))
                        .putExtra("variant", transfer.variant).putExtra("download", transfer.id)
                        .putExtra("packageId", candidate.packageName).putExtra("version", candidate.code())
                    val callback = PendingIntent.getBroadcast(app, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    app.dao.updateTransfer(transfer.variant, transfer.id, "installing", "")
                    session.commit(callback.intentSender)
                }
            } catch (e: Exception) {
                sessionId?.let { runCatching { installer.abandonSession(it) } }
                val label = (e as? ApkProblem)?.label ?: R.string.install_failed
                app.dao.updateTransfer(transfer.variant, transfer.id, "ready", app.getString(label))
            }
        }
    }

    private fun validate(file: File, transfer: Transfer): PackageInfo {
        if (transfer.bytes > 0 && file.length() != transfer.bytes) throw ApkProblem(R.string.bad_size)
        if (transfer.digest != null && sha256(file) != transfer.digest.substringAfter(':').lowercase()) {
            throw ApkProblem(R.string.bad_digest)
        }
        val candidate = app.packageManager.getPackageArchiveInfo(file.path, signatureFlag())
            ?: throw ApkProblem(R.string.bad_apk)
        if (candidate.signers().isEmpty() || candidate.code() < 0) throw ApkProblem(R.string.bad_apk)
        if (transfer.packageId.isNotBlank() && candidate.packageName != transfer.packageId) {
            throw ApkProblem(R.string.bad_package)
        }
        if ((candidate.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) throw ApkProblem(R.string.bad_abi)
        val supported = Build.SUPPORTED_ABIS.toSet()
        val nativeAbis = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .map { it.name.split('/')[1] }.toSet()
        }
        if (nativeAbis.isNotEmpty() && nativeAbis.none { it in supported }) throw ApkProblem(R.string.bad_abi)
        val installed = app.packageManager.visiblePackage(candidate.packageName)
        if (installed != null) {
            if (!candidate.canUpdate(installed)) throw ApkProblem(R.string.bad_signature)
            if (candidate.code() <= installed.code()) throw ApkProblem(R.string.same_or_older)
        }
        return candidate
    }

    suspend fun installResult(intent: Intent) {
        val variant = intent.getStringExtra("variant") ?: return
        val download = intent.getLongExtra("download", -1)
        val transfer = app.dao.transfer(variant) ?: return
        if (transfer.id != download) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (prompt != null) approval.value = PendingApproval(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1), prompt)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra("packageId") ?: return
                val version = intent.getLongExtra("version", -1)
                app.dao.installed(variant, pkg, version, transfer.tag, transfer.publishedAt, transfer.assetId, transfer.fingerprint)
                manager.remove(download)
                app.dao.deleteTransfer(variant, download)
            }
            else -> {
                val label = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.bad_signature
                    PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_cancelled
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_blocked
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.bad_abi
                    else -> R.string.install_failed
                }
                app.dao.updateTransfer(variant, download, "ready", app.getString(label))
            }
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        receiverScope.launch {
            try { (context.applicationContext as PortarioApp).downloads.onDownloadComplete(id) }
            finally { pending.finish() }
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        receiverScope.launch {
            try { (context.applicationContext as PortarioApp).downloads.installResult(intent) }
            finally { pending.finish() }
        }
    }
}
