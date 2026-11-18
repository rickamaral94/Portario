package br.com.amaral.portario

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class UpdateIntegrationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PortarioApp
    private lateinit var database: PortarioDatabase
    private val clock = AtomicLong(1_700_000_000_000L)
    private val followed = GithubRef("portario-test/followed")
    private val other = GithubRef("portario-test/other")

    @Before
    fun createStore() {
        database = Room.inMemoryDatabaseBuilder(app, PortarioDatabase::class.java).build()
    }

    @After
    fun closeStore() {
        database.close()
    }

    @Test
    fun rateLimitPersistsAcrossRepositoriesAndPreservesConditionalCache() = runBlocking {
        val calls = AtomicInteger()
        val http = client { request ->
            when (calls.incrementAndGet()) {
                1 -> reply(request, 200, release("v1"), "ETag" to "\"release-v1\"")
                2 -> reply(request, 429, "{}", "Retry-After" to "90")
                3 -> {
                    assertEquals("\"release-v1\"", request.header("If-None-Match"))
                    reply(request, 304, "")
                }
                else -> error("Unexpected HTTP request")
            }
        }
        val first = ReleaseRepository(database.dao(), http, clock::get)
        assertEquals("v1", first.check(followed).single().tag)
        clock.addAndGet(30_000)
        val blocked = clock.get() + 90_000
        val limited = failure { first.check(followed, force = true) }
        assertEquals(429, limited.code)
        assertEquals(blocked, limited.retryAt)
        assertTrue(limited.retryable)

        val reopened = ReleaseRepository(database.dao(), http, clock::get)
        assertEquals(blocked, failure { reopened.check(other) }.retryAt)
        val saved = requireNotNull(database.dao().cache(ReleaseRepository.cacheKey(followed)))
        assertEquals("v1", ReleaseRepository.decode(saved).single().tag)
        assertEquals(2, calls.get())

        clock.set(blocked)
        assertEquals("v1", reopened.check(followed, force = true).single().tag)
        assertEquals(blocked, database.dao().cache(ReleaseRepository.cacheKey(followed))?.checkedAt)
        assertEquals(3, calls.get())
    }

    @Test
    fun permissionDeniedDoesNotBlockOtherSourcesAndPrimaryLimitUsesReset() = runBlocking {
        val reset = clock.get() + 120_000
        val http = client { request ->
            when {
                request.url.pathSegments.contains("denied") -> reply(request, 403, """{"message":"Forbidden"}""")
                request.url.pathSegments.contains("limited") -> reply(
                    request, 403, "{}", "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to (reset / 1_000).toString()
                )
                else -> reply(request, 200, release("v1"))
            }
        }
        val source = ReleaseRepository(database.dao(), http, clock::get)
        val denied = failure { source.check(GithubRef("portario-test/denied")) }
        assertEquals(403, denied.code)
        assertFalse(denied.retryable)
        assertEquals("v1", source.check(other).single().tag)
        assertEquals(reset, failure { source.check(GithubRef("portario-test/limited")) }.retryAt)
        assertEquals(reset, failure { source.check(followed) }.retryAt)
    }

    @Test
    fun secondaryLimitWithoutHeadersWaitsOneMinute() = runBlocking {
        val http = client { request ->
            reply(request, 403, """{"message":"You have exceeded a secondary rate limit."}""")
        }
        val source = ReleaseRepository(database.dao(), http, clock::get)
        val limited = failure { source.check(followed) }
        assertEquals(429, limited.code)
        assertEquals(clock.get() + 60_000, limited.retryAt)
    }

    @Test
    fun optInRunsTwoPeriodicChecksAndOptOutCancelsTheWork() = runBlocking {
        val calls = AtomicInteger()
        val http = client { request -> reply(request, 200, release("v${calls.incrementAndGet()}")) }
        val source = ReleaseRepository(app.dao, http, clock::get)
        val config = Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = if (workerClassName == UpdateWorker::class.java.name) {
                UpdateWorker(appContext, workerParameters, source)
            } else null
        }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, config, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS
        )
        val work = WorkManager.getInstance(app)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(app))
        val unique = "portario-release-check"
        val cacheKey = ReleaseRepository.cacheKey(followed)
        try {
            assertFalse(app.checksEnabled.first())
            assertNull(app.dao.cache(cacheKey))
            app.dao.save(Track(followed.key, followed.repository, Channel.ALL.name, preferGuide = true))
            app.setChecksEnabled(true)
            assertTrue(app.checksEnabled.first())
            val firstEnqueue = withTimeout(20_000) {
                work.getWorkInfosForUniqueWorkFlow(unique).first { it.isNotEmpty() }.single()
            }
            assertEquals(TimeUnit.HOURS.toMillis(6), firstEnqueue.periodicityInfo?.repeatIntervalMillis)
            driver.setAllConstraintsMet(firstEnqueue.id)
            awaitTag(cacheKey, "v1")
            withTimeout(20_000) {
                work.getWorkInfoByIdFlow(firstEnqueue.id).first {
                    it != null && it.state == WorkInfo.State.ENQUEUED &&
                        it.nextScheduleTimeMillis > System.currentTimeMillis() + TimeUnit.HOURS.toMillis(5)
                }
            }

            clock.addAndGet(TimeUnit.HOURS.toMillis(6))
            driver.setPeriodDelayMet(firstEnqueue.id)
            driver.setAllConstraintsMet(firstEnqueue.id)
            awaitTag(cacheKey, "v2")
            assertEquals(2, calls.get())
            app.setChecksEnabled(false)
            withTimeout(20_000) {
                work.getWorkInfoByIdFlow(firstEnqueue.id).first { it?.state == WorkInfo.State.CANCELLED }
            }
            assertFalse(app.checksEnabled.first())
        } finally {
            app.setChecksEnabled(false)
            app.dao.deleteTrack(followed.key)
        }
    }

    private suspend fun awaitTag(cacheKey: String, tag: String) = withTimeout(20_000) {
        while (app.dao.cache(cacheKey)?.let(ReleaseRepository::decode)?.singleOrNull()?.tag != tag) {
            delay(50)
        }
    }

    private suspend fun failure(block: suspend () -> Unit): SourceFailure {
        try {
            block()
        } catch (expected: SourceFailure) {
            return expected
        }
        error("Expected a GitHub response failure")
    }

    private fun client(response: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { chain -> response(chain.request()) }.build()

    private fun release(tag: String) = """[{"tag_name":"$tag","published_at":"2025-01-01T00:00:00Z","assets":[]}]"""

    private fun reply(request: Request, code: Int, body: String, vararg headers: Pair<String, String>) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody()).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
}
