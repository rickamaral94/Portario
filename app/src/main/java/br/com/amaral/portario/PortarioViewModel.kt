package br.com.amaral.portario

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Destination { EXPLORE, LIBRARY, ADD }

data class SourcePreview(val ref: GithubRef, val releases: List<Release>, val track: Track?, val variant: Variant)

class PortarioViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as PortarioApp
    private val dao = app.dao
    val notices = MutableSharedFlow<Int>(extraBufferCapacity = 5)
    val tracks = dao.tracks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val caches = dao.caches().map { items -> items.filter { it.json.isNotBlank() }.associateBy { it.endpoint } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val transfers = dao.transfers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val periodic = app.checksEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val catalog = flow { emit(withContext(Dispatchers.IO) { app.loadCatalog() }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val games = combine(catalog, tracks) { seed, followed ->
        val known = seed.flatMap { it.variants }.map { it.id }.toSet()
        seed + followed.filter { it.id !in known && it.id == GithubRef(it.repository).key }.map { track ->
            val variant = Variant.custom(GithubRef(track.repository))
            Game(variant.gameId, variant.name, listOf(variant.repository), "", "", listOf(variant))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val progress = transfers.flatMapLatest { items ->
        flow {
            val active = items.filter { it.status == "downloading" }
            do {
                emit(active.associate { it.variant to app.downloads.progress(it) })
                if (active.isNotEmpty()) delay(650)
            } while (active.isNotEmpty())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    var destination by mutableStateOf(Destination.EXPLORE)
    var selectedGame by mutableStateOf<String?>(null)
    var selectedVariant by mutableStateOf<String?>(null)
    var lastGame by mutableStateOf<String?>(null)
    var input by mutableStateOf("")
    var preview by mutableStateOf<SourcePreview?>(null)
        private set
    var addError by mutableStateOf<Int?>(null)
        private set
    var adding by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var refreshingId by mutableStateOf<String?>(null)
        private set
    var packageEpoch by mutableStateOf(0)
        private set

    fun onForeground() { packageEpoch++ }

    fun navigate(next: Destination) {
        selectedGame = null
        selectedVariant = null
        lastGame = null
        destination = next
    }

    fun show(game: Game, variant: Variant = game.variants.first()) {
        selectedGame = game.id
        selectedVariant = variant.id
        lastGame = game.id
        if (caches.value[ReleaseRepository.cacheKey(variant.withTrack(tracks.value.find { it.id == variant.id }))] == null) {
            check(variant, false, openAssets = false)
        }
    }

    fun closeDetail() { selectedGame = null; selectedVariant = null }

    fun edit(value: String) {
        input = value
        preview = null
        addError = null
    }

    fun inspect() {
        if (adding) return
        val ref = parseGithubRef(input)
        if (ref == null) { addError = R.string.invalid_url; return }
        adding = true
        addError = null
        viewModelScope.launch {
            try {
                val releases = app.releases.check(ref, true)
                val candidate = catalog.value.flatMap { it.variants }.find { it.ref.key == ref.key } ?: Variant.custom(ref)
                if (parseGithubRef(input) == ref) preview = SourcePreview(ref, releases, dao.track(candidate.id), candidate)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (parseGithubRef(input) == ref) addError = errorLabel(error) }
            finally { adding = false }
        }
    }

    fun accept(source: SourcePreview, channel: Channel, filter: String, pinned: Boolean, picked: String) = launchAction {
        val variant = source.variant
        val existing = dao.track(variant.id)
        val tag = source.ref.tag.takeIf { pinned }
        val initialRelease = if (source.ref.tag == null || pinned)
            selectedRelease(source.releases, source.ref, channel, filter, variant.setup == Setup.MANUAL) else null
        val baseline = initialRelease?.fingerprint().orEmpty()
        if (existing == null) dao.save(defaultTrack(variant).copy(channel = channel.name, filter = filter.trim(), pinnedTag = tag,
            assetName = picked, notifiedTag = baseline, followedRelease = baseline))
        else if (existing.channel != channel.name || existing.filter != filter.trim() || existing.pinnedTag != tag || existing.assetName != picked)
            dao.configure(variant.id, channel.name, filter.trim(), tag, picked, baseline)
        if (source.ref.tag != null && !pinned) check(variant, false, openAssets = false)
        edit("")
        navigate(Destination.LIBRARY)
        notices.tryEmit(if (existing == null) R.string.follow_ok else R.string.source_updated)
    }

    fun toggleFollow(variant: Variant) = launchAction {
        if (dao.track(variant.id) == null) {
            ensureTrack(variant)
            check(variant, false, openAssets = false)
            notices.tryEmit(R.string.follow_ok)
        } else {
            app.dao.transfer(variant.id)?.let { app.downloads.cancel(it) }
            dao.deleteTrack(variant.id)
            notices.tryEmit(R.string.unfollow_ok)
        }
    }

    fun favorite(variant: Variant) = launchAction {
        ensureTrack(variant)
        dao.toggleFavorite(variant.id)
    }

    fun checkAll() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                val result = refreshTracked(app, true, false)
                notices.tryEmit(result.failures.firstOrNull()?.let(::errorLabel) ?: R.string.checked_all)
            } finally { refreshing = false; onForeground() }
        }
    }

    fun setAutomatic(enabled: Boolean) = launchAction { app.setChecksEnabled(enabled) }

    fun check(variant: Variant, force: Boolean, openAssets: Boolean, finished: (Release) -> Unit = {}) {
        viewModelScope.launch {
            if (refreshingId == variant.id) return@launch
            refreshingId = variant.id
            try {
                val track = dao.track(variant.id)
                val ref = variant.withTrack(track)
                val releases = app.releases.check(ref, force)
                val release = selectedRelease(releases, ref, track?.let { Channel.valueOf(it.channel) } ?: variant.channel,
                    track?.filter ?: variant.filter, variant.setup == Setup.MANUAL)
                if (track != null && release != null) dao.setInitialRelease(track.id, release.fingerprint())
                if (openAssets) {
                    if (release != null) finished(release) else notices.tryEmit(R.string.no_apk)
                } else if (force) notices.tryEmit(R.string.checked_source)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (force || openAssets) notices.tryEmit(errorLabel(error)) }
            finally { if (refreshingId == variant.id) refreshingId = null }
        }
    }

    fun download(variant: Variant, release: Release, asset: Asset) = launchAction {
        ensureTrack(variant)
        dao.selectAsset(variant.id, asset.name, release.fingerprint())
        app.downloads.enqueue(variant, release, asset)
    }

    fun cancel(transfer: Transfer) = launchAction { app.downloads.cancel(transfer) }
    fun install(transfer: Transfer) = launchAction { app.downloads.install(transfer) }

    private suspend fun ensureTrack(variant: Variant): Track =
        dao.track(variant.id) ?: defaultTrack(variant).also { dao.save(it) }

    private fun defaultTrack(variant: Variant): Track {
        val current = caches.value[ReleaseRepository.cacheKey(variant.ref)]?.let(ReleaseRepository::decode)
        val latest = current?.let { selectedRelease(it, variant.ref, variant.channel, variant.filter, variant.setup == Setup.MANUAL) }
        return Track(variant.id, variant.repository, variant.channel.name, variant.filter,
            packageId = variant.packageId, notifiedTag = latest?.fingerprint().orEmpty(),
            followedRelease = latest?.fingerprint().orEmpty(), preferGuide = variant.setup == Setup.MANUAL)
    }

    private fun errorLabel(error: Exception): Int = when ((error as? SourceFailure)?.code) {
        404 -> R.string.source_missing
        429 -> R.string.rate_limit
        else -> R.string.network_error
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notices.tryEmit(R.string.operation_error) }
        }
    }
}
