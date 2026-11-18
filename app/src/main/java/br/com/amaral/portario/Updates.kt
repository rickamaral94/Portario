package br.com.amaral.portario

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface ReleaseSource {
    suspend fun check(ref: GithubRef, force: Boolean = false): List<Release>
}

class SourceFailure(val code: Int, val retryAt: Long = 0) : IOException() {
    val retryable get() = code == 429 || code == 408 || code in 500..599
}

class ReleaseRepository(
    private val dao: PortarioDao,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(22, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis
) : ReleaseSource {
    private val activeRequests = Semaphore(3)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val backoffLock = Mutex()
    private val backoffKey = "github-rate-limit"

    override suspend fun check(ref: GithubRef, force: Boolean): List<Release> = withContext(Dispatchers.IO) {
        val endpoint = cacheKey(ref)
        locks.getOrPut(endpoint) { Mutex() }.withLock {
            val old = dao.cache(endpoint)
            val now = clock()
            if (old != null && now - old.checkedAt < if (force) 25_000L else 20 * 60_000L) {
                return@withLock decode(old)
            }
            activeRequests.withPermit {
                val retryAt = dao.cache(backoffKey)?.checkedAt ?: 0
                if (clock() < retryAt) throw SourceFailure(429, retryAt)
                val url = HttpUrl.Builder().scheme("https").host("api.github.com")
                    .addPathSegment("repos").apply { ref.repository.split('/').forEach { addPathSegment(it) } }
                    .addPathSegment("releases").apply {
                        val tag = ref.tag
                        if (tag == null) addQueryParameter("per_page", "30")
                        else addPathSegment("tags").addPathSegment(tag)
                    }.build()
                val request = Request.Builder().url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Portario/0.1")
                    .apply { old?.etag?.let { header("If-None-Match", it) } }.build()
                client.newCall(request).execute().use { response ->
                    val checkedAt = clock()
                    if (response.code == 304 && old != null) {
                        return@use decode(old.copy(checkedAt = checkedAt).also { dao.save(it) })
                    }
                    if (!response.isSuccessful) {
                        response.githubRetryAt(checkedAt)?.let { proposed ->
                            val until = backoffLock.withLock {
                                maxOf(proposed, dao.cache(backoffKey)?.checkedAt ?: 0).also {
                                    dao.save(ReleaseCache(backoffKey, "", null, it))
                                }
                            }
                            throw SourceFailure(429, until)
                        }
                        throw SourceFailure(response.code)
                    }
                    val new = ReleaseCache(endpoint, response.body?.string() ?: "[]", response.header("ETag"), checkedAt)
                    val decoded = decode(new)
                    dao.save(new)
                    decoded
                }
            }
        }
    }

    companion object {
        fun cacheKey(ref: GithubRef) = ref.key + (ref.tag?.let { "#$it" } ?: "")

        fun decode(cache: ReleaseCache): List<Release> = with(cache.json) {
            if (trimStart().startsWith("[")) JSONArray(this).objects().filterNot { it.optBoolean("draft") }.map(Release::from)
            else JSONObject(this).let { if (it.optBoolean("draft")) emptyList() else listOf(Release.from(it)) }
        }
    }
}

private fun Response.githubRetryAt(now: Long): Long? {
    if (code != 403 && code != 429) return null
    val retryAfter = header("Retry-After")?.toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE - now) / 1_000 }
    val exhausted = header("X-RateLimit-Remaining") == "0"
    val reset = header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { exhausted && it in 1..Long.MAX_VALUE / 1_000 }
    val limited = code == 429 || retryAfter != null || exhausted ||
        peekBody(4096).string().contains("rate limit", ignoreCase = true)
    if (!limited) return null
    return maxOf(retryAfter?.let { now + it * 1_000 } ?: 0, reset?.times(1_000) ?: 0)
        .takeIf { it > now } ?: (now + 60_000)
}

fun Variant.withTrack(track: Track?): GithubRef = GithubRef(repository, track?.pinnedTag)

fun selectedRelease(releases: List<Release>, ref: GithubRef, channel: Channel, filter: String, preferGuide: Boolean = false): Release? {
    if (ref.tag != null) return releases.firstOrNull { it.tag == ref.tag }
    if (preferGuide) {
        val sorted = releases.sortedByDescending { it.publishedAt }
        return when (channel) {
            Channel.STABLE -> sorted.firstOrNull { !it.beta } ?: sorted.firstOrNull()
            Channel.BETA -> sorted.firstOrNull { it.beta }
            Channel.ALL -> sorted.firstOrNull()
        }
    }
    return pickRelease(releases, channel, Build.SUPPORTED_ABIS.toList(), filter)
}

data class RefreshResult(val failures: List<Exception>)

suspend fun refreshTracked(
    app: PortarioApp,
    force: Boolean,
    notify: Boolean,
    source: ReleaseSource = app.releases
): RefreshResult = coroutineScope {
    val failures = app.dao.trackedNow().chunked(3).flatMap { batch ->
        batch.map { track -> async {
            try {
                val ref = GithubRef(track.repository, track.pinnedTag)
                val releases = source.check(ref, force)
                val release = selectedRelease(releases, ref, Channel.valueOf(track.channel), track.filter, track.preferGuide)
                if (release != null) app.dao.setInitialRelease(track.id, release.fingerprint())
                if (notify && release != null && track.pinnedTag == null) notifyRelease(app, track, release)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
        } }.awaitAll()
    }.filterNotNull()
    RefreshResult(failures)
}

private suspend fun notifyRelease(app: PortarioApp, track: Track, release: Release) {
    val fingerprint = release.fingerprint()
    if (fingerprint == track.notifiedTag ||
        (release.tag == track.installedTag && release.assets.any { it.id == track.installedAssetId }) ||
        (track.installedPublishedAt > 0 && release.publishedAt < track.installedPublishedAt)
    ) return
    if (track.notifiedTag.isBlank() && track.installedTag.isBlank()) {
        if (track.followedRelease.isBlank() || track.followedRelease == fingerprint) {
            app.dao.markNotified(track.id, fingerprint)
            return
        }
    }
    if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = app.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return
    val channel = "releases"
    manager.createNotificationChannel(NotificationChannel(channel, app.getString(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).setAction(MainActivity.OPEN_LIBRARY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = Notification.Builder(app, channel).setSmallIcon(R.drawable.ic_portal_mono)
        .setContentTitle(app.getString(R.string.release_alert, track.repository.substringAfter('/')))
        .setContentText(release.tag).setContentIntent(open).setAutoCancel(true).build()
    manager.notify(track.id, 1, notification)
    app.dao.markNotified(track.id, fingerprint)
}

class UpdateWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val source: ReleaseSource
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) :
        this(context, parameters, (context.applicationContext as PortarioApp).releases)

    override suspend fun doWork(): Result {
        val result = refreshTracked(applicationContext as PortarioApp, force = false, notify = true, source = source)
        return if (result.failures.any { if (it is SourceFailure) it.retryable else it is IOException }) {
            Result.retry()
        } else Result.success()
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            val name = "portario-release-check"
            if (!enabled) {
                workManager.cancelUniqueWork(name)
                return
            }
            val work = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
