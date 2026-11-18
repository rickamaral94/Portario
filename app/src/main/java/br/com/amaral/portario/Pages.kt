package br.com.amaral.portario

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private val kinds = Kind.entries
private val filterLabels = listOf(R.string.genre, R.string.platform, R.string.type, R.string.stage, R.string.controller)
private val abis get() = Build.SUPPORTED_ABIS.toList()

fun matchesFilters(game: Game, search: String, filters: List<String>): Boolean =
    game.matches(search) && (filters[0].isEmpty() || game.genre == filters[0]) &&
        (filters[1].isEmpty() || game.platform == filters[1]) &&
        game.variants.any { v ->
            (filters[2].isEmpty() || v.kind.name == filters[2]) &&
                (filters[3].isEmpty() || v.stage == filters[3]) &&
                (filters[4].isEmpty() || v.controller.ifBlank { "unknown" } == filters[4])
        }

private val Kind.label get() = when (this) {
    Kind.RECOMP -> R.string.kind_recomp
    Kind.DECOMP -> R.string.kind_decomp
    Kind.SOURCE -> R.string.kind_source
    Kind.ENGINE -> R.string.kind_engine
}

private val Setup.label get() = when (this) {
    Setup.DIRECT -> R.string.setup_direct
    Setup.EXTRA -> R.string.setup_extra
    Setup.MANUAL -> R.string.setup_manual
}

private val Channel.label get() = when (this) {
    Channel.STABLE -> R.string.channel_stable
    Channel.ALL -> R.string.channel_all
    Channel.BETA -> R.string.channel_beta
}

@Composable
private fun controllerLabel(value: String) = stringResource(when (value) {
    "yes" -> R.string.controller_yes
    "no" -> R.string.controller_no
    else -> R.string.unknown
})

@Composable
private fun stageLabel(value: String) = when (value) {
    "Estável" -> stringResource(R.string.stable)
    "Beta" -> stringResource(R.string.beta)
    "Experimental" -> stringResource(R.string.experimental)
    "Jogável" -> stringResource(R.string.playable)
    "Alpha" -> stringResource(R.string.alpha)
    else -> value
}

@Composable
fun FiltersDialog(games: List<Game>, chosen: List<String>, select: (List<String>) -> Unit, close: () -> Unit) {
    var values by remember { mutableStateOf(chosen) }
    val options = listOf(games.map { it.genre }, games.map { it.platform }, kinds.map { it.name },
        games.flatMap { it.variants }.map { it.stage }, listOf("yes", "no", "unknown"))
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.filters)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            options.forEachIndexed { index, entries ->
                val labels = entries.filter(String::isNotBlank).distinct().sorted().map { value ->
                    value to when (index) {
                        2 -> stringResource(Kind.valueOf(value).label)
                        3 -> stageLabel(value)
                        4 -> controllerLabel(value)
                        else -> value
                    }
                }
                Choice(stringResource(filterLabels[index]), values[index],
                    listOf("" to stringResource(R.string.all)) + labels) { value ->
                    values = values.toMutableList().also { it[index] = value }.toList()
                }
            }
        }
    }, confirmButton = { Action(stringResource(R.string.apply), primary = true) { select(values); close() } },
        dismissButton = { Action(stringResource(R.string.clear)) { select(listOf("", "", "", "", "")); close() } })
}

@Composable
private fun Choice(label: String, value: String, choices: List<Pair<String, String>>, select: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box {
        Action("$label · ${choices.find { it.first == value }?.second.orEmpty()}  ▾",
            Modifier.fillMaxWidth()) { show = true }
        DropdownMenu(show, { show = false }) {
            choices.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { select(key); show = false },
                    modifier = Modifier.focusRing())
            }
        }
    }
}

@Composable
fun LibraryPage(model: PortarioViewModel, games: List<Game>, tracks: List<Track>, caches: Map<String, ReleaseCache>,
                transfers: Map<String, Transfer>, periodic: Boolean, list: LazyListState, askNotifications: () -> Unit) {
    val context = LocalContext.current
    val byId = games.flatMap { g -> g.variants.map { it.id to (g to it) } }.toMap()
    val epoch = model.packageEpoch
    val installed = remember(tracks, epoch) {
        tracks.associate { it.id to context.packageManager.visiblePackage(it.packageId) }
    }
    val current = remember(tracks, caches, byId) {
        tracks.associate { track ->
            val ref = GithubRef(track.repository, track.pinnedTag)
            val cached = caches[ReleaseRepository.cacheKey(ref)]?.let(ReleaseRepository::decode).orEmpty()
            track.id to selectedRelease(cached, ref, Channel.valueOf(track.channel), track.filter, track.preferGuide)
        }
    }
    val updates = tracks.count { publication(current[it.id], it, installed[it.id] != null, installed[it.id]?.code()) == Publication.NEW_RELEASE }
    val ordered = tracks.sortedByDescending { track ->
        (if (track.favorite) 2 else 0) + if (publication(current[track.id], track,
            installed[track.id] != null, installed[track.id]?.code()) == Publication.NEW_RELEASE) 4 else 0
    }
    val restore = model.lastVariant.takeIf { model.selectedGame == null }
    val focus = remember { FocusRequester() }
    LaunchedEffect(restore) {
        if (restore != null) {
            val index = ordered.indexOfFirst { it.id == restore }
            if (index >= 0) {
                if (list.layoutInfo.visibleItemsInfo.none { it.index == index + 2 }) list.scrollToItem(index + 2)
                delay(60)
                runCatching { focus.requestFocus() }
            }
        }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(3.dp))
            Note(stringResource(R.string.library_count, tracks.size, updates))
            Spacer(Modifier.height(9.dp))
            Action(stringResource(if (model.refreshing) R.string.checking else R.string.check_updates), primary = true,
                enabled = tracks.isNotEmpty() && !model.refreshing) { model.checkAll() }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.automatic))
                    Note(stringResource(R.string.automatic_note))
                }
                Switch(periodic, {
                    model.setAutomatic(it)
                    if (it) askNotifications()
                }, modifier = Modifier.focusRing())
            }
        }
        if (tracks.isEmpty()) item {
            Note(stringResource(R.string.library_empty), modifier = Modifier.padding(top = 8.dp))
        }
        items(ordered, key = { it.id }) { track ->
            val (game, variant) = byId[track.id] ?: return@items
            val release = current[track.id]
            val info = installed[track.id]
            val packageState = publication(release, track, info != null, info?.code())
            val transfer = transfers[track.id]
            val label = when {
                transfer?.status == "downloading" -> R.string.downloading
                transfer?.status == "ready" -> R.string.ready
                packageState == Publication.NEW_RELEASE -> R.string.new_release
                packageState == Publication.CHECK_VERSION -> R.string.compare_version
                packageState == Publication.INSTALLED -> R.string.installed
                track.favorite -> R.string.favorite
                track.packageId.isNotBlank() -> R.string.unknown_installation
                else -> null
            }
            GameRow(game, variant, if (track.id == restore) Modifier.focusRequester(focus) else Modifier,
                status = label?.let { stringResource(it) }) { model.show(game, variant) }
        }
    }
}

@Composable
fun AddPage(model: PortarioViewModel) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(stringResource(R.string.add_source), style = MaterialTheme.typography.headlineMedium)
        Note(stringResource(R.string.add_instructions))
        OutlinedTextField(model.input, model::edit, Modifier.fillMaxWidth().focusRing(),
            label = { Text(stringResource(R.string.github_url)) }, singleLine = true, isError = model.addError != null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(stringResource(R.string.paste)) {
                scope.launch {
                    clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()?.let(model::edit)
                }
            }
            Action(stringResource(if (model.adding) R.string.checking else R.string.preview), primary = true,
                enabled = model.input.isNotBlank() && !model.adding, click = model::inspect)
        }
        model.addError?.let { Note(stringResource(it), MaterialTheme.colorScheme.error) }
        val preview = model.preview
        if (preview != null) key(preview.ref) { PreviewCard(preview, model) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(preview: SourcePreview, model: PortarioViewModel) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var channel by rememberSaveable { mutableStateOf(preview.track?.let { Channel.valueOf(it.channel) } ?: preview.variant.channel) }
    var filter by rememberSaveable { mutableStateOf(preview.track?.filter ?: preview.variant.filter) }
    var pinned by rememberSaveable { mutableStateOf(preview.ref.tag != null && preview.track?.pinnedTag == preview.ref.tag) }
    var asset by rememberSaveable { mutableStateOf(preview.track?.assetName.orEmpty()) }
    val release = remember(preview, channel, filter) {
        selectedRelease(preview.releases, preview.ref, channel, filter, preview.variant.setup == Setup.MANUAL)
    }
    val options = release?.compatibleAssets(abis, filter).orEmpty()
    val preferred = release?.recommended(abis, filter)?.name.orEmpty()
    val choice = preferredAsset(options, asset)?.name ?: preferred
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Panel).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(preview.ref.repository.substringAfter('/'), style = MaterialTheme.typography.titleLarge)
        Note(preview.ref.repository)
        Note(stringResource(R.string.channel_label, stringResource(channel.label)))
        if (release != null) {
            Text(release.tag + if (release.beta) " · " + stringResource(R.string.beta) else "", color = Mint)
            if (options.isEmpty()) Note(stringResource(if (release.assets.any { it.name.endsWith(".apk", true) })
                R.string.no_compatible_apk else R.string.no_apk))
            else if (options.size == 1) Note(options[0].name + " · " + readableBytes(options[0].bytes))
            else Choice(stringResource(R.string.apk), choice, listOf("" to stringResource(R.string.choose_apk)) +
                options.map { it.name to it.name }) { asset = it }
        } else Note(stringResource(R.string.no_apk))
        if (preview.ref.tag != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(pinned, { pinned = it }, modifier = Modifier.focusRing())
            Text(stringResource(R.string.pin_tag))
        }
        if (advanced) {
            Choice(stringResource(R.string.channel), channel.name, Channel.entries.map { it.name to stringResource(it.label) }) {
                channel = Channel.valueOf(it)
            }
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().focusRing(), singleLine = true,
                label = { Text(stringResource(R.string.asset_filter)) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(stringResource(R.string.follow), primary = true) {
                model.accept(preview, channel, filter, pinned, choice)
            }
            Action(stringResource(if (advanced) R.string.hide_advanced else R.string.advanced)) { advanced = !advanced }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailPage(game: Game, variant: Variant, track: Track?, cache: ReleaseCache?, transfer: Transfer?,
               progress: DownloadProgress?, model: PortarioViewModel, install: (Transfer) -> Unit,
               openPackage: (String) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val epoch = model.packageEpoch
    val packageId = track?.packageId?.ifBlank { variant.packageId } ?: variant.packageId
    val info = remember(packageId, epoch, track, transfer) { context.packageManager.visiblePackage(packageId) }
    val matches = remember(cache) { cache?.let(ReleaseRepository::decode).orEmpty() }
    val channel = track?.let { Channel.valueOf(it.channel) } ?: variant.channel
    val current = selectedRelease(matches, variant.withTrack(track), channel, track?.filter ?: variant.filter,
        variant.setup == Setup.MANUAL)
    val options = current?.compatibleAssets(abis, track?.filter ?: variant.filter).orEmpty()
    val wrongAbi = current?.tag == variant.verifiedTag && variant.knownAbis.isNotEmpty() && variant.knownAbis.none { it in abis }
    val code = info?.code()
    val publication = publication(current, track, info != null, code)
    val variantsConflict = game.variants.count { candidate ->
        candidate.packageId.isNotBlank() && candidate.packageId == packageId
    } > 1
    var chooser by remember(variant.id) { mutableStateOf<Release?>(null) }
    var confirmingUnfollow by remember { mutableStateOf(false) }
    var confirmManual by remember(variant.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(variant.id) { scroll.scrollTo(0) }

    Column(modifier.fillMaxHeight().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Action(stringResource(R.string.back), click = model::closeDetail)
        Cover(game.genre, Modifier.fillMaxWidth().height(if (compact) 58.dp else 96.dp).clip(RoundedCornerShape(15.dp)))
        Text(game.title, style = MaterialTheme.typography.headlineMedium)
        Note(listOf(game.platform, game.genre).filter(String::isNotBlank).joinToString(" · "), Mint)
        if (game.variants.size > 1) {
            Choice(stringResource(R.string.variant), variant.id, game.variants.map { it.id to it.name }) { chosen ->
                game.variants.find { it.id == chosen }?.let { model.show(game, it) }
            }
        }
        Text(variant.name, style = MaterialTheme.typography.titleLarge)
        Note(variant.repository)
        if (variant.upstream.isNotBlank()) Note(stringResource(R.string.upstream, variant.upstream))
        if (variant.stage.isNotBlank()) Note(stageLabel(variant.stage), Violet)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Action(stringResource(if (track == null) R.string.follow else R.string.following), primary = track == null) {
                if (track == null) model.toggleFollow(variant) else confirmingUnfollow = true
            }
            Action(stringResource(if (track?.favorite == true) R.string.unfavorite else R.string.favorite)) { model.favorite(variant) }
            Action(stringResource(R.string.check_now), enabled = model.refreshingId != variant.id) { model.check(variant, true, false) }
        }
        Metadata(R.string.setup, stringResource(variant.setup.label))
        Metadata(R.string.game_files, variant.gameFiles.ifBlank { stringResource(R.string.files_unknown) })
        if (variantsConflict) Note(stringResource(R.string.package_conflict, packageId), Violet)
        if (variant.note.isNotBlank()) Note(variant.note, Violet)
        HorizontalDivider(color = Edge)
        Text(stringResource(R.string.source_release), style = MaterialTheme.typography.titleLarge)
        Note(stringResource(R.string.channel_label, stringResource(channel.label)))
        if (track?.pinnedTag != null) Note(stringResource(R.string.pinned_to, track.pinnedTag))
        if (current != null) {
            Text(current.tag + if (current.beta) " · " + stringResource(R.string.beta) else "", color = Mint)
            if (options.isEmpty() || wrongAbi) {
                Note(stringResource(if (current.assets.any { it.name.endsWith(".apk", true) })
                    R.string.no_compatible_apk else R.string.no_apk))
            }
        } else if (cache != null) Note(stringResource(R.string.no_apk))
        else if (variant.verifiedTag.isNotBlank()) {
            Note(stringResource(if (variant.verifiedAssets.isEmpty()) R.string.catalog_release_manual
                else R.string.catalog_release, variant.verifiedTag), Mint)
        } else Note(stringResource(R.string.release_unchecked))
        if (cache != null) Note(stringResource(R.string.last_check, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cache.checkedAt))))
        else if (variant.checked.isNotBlank()) Note(stringResource(R.string.catalog_date, variant.checked))
        if (info != null) Note(stringResource(R.string.installed_code, info.versionName.orEmpty(), code ?: 0L), Mint)
        else if (packageId.isNotBlank()) Note(stringResource(R.string.unknown_installation))
        if (publication == Publication.NEW_RELEASE) Note(stringResource(R.string.new_release_compare), Violet)
        if (transfer != null) TransferCard(transfer, progress, model, install)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (info != null) Action(stringResource(R.string.open), primary = true) { openPackage(packageId) }
            if (transfer == null && !wrongAbi && (current == null && cache == null && variant.setup != Setup.MANUAL ||
                current != null && options.isNotEmpty())) {
                val label = when (publication) {
                    Publication.NEW_RELEASE -> R.string.download_update
                    Publication.CHECK_VERSION -> R.string.compare_version
                    Publication.INSTALLED -> R.string.view_apk
                    else -> R.string.download_apk
                }
                Action(stringResource(label), primary = info == null) {
                    if (variant.setup == Setup.MANUAL) confirmManual = true
                    else if (current != null) chooser = current
                    else model.check(variant, false, true) { chooser = it }
                }
            }
            if (variant.guide.isNotBlank()) Action(stringResource(R.string.guide)) { context.openUrl(variant.guide) }
            Action(stringResource(R.string.repository)) { context.openUrl(variant.ref.url) }
            if (!current?.url.isNullOrBlank()) Action(stringResource(R.string.release_link)) { context.openUrl(current!!.url) }
        }
        HorizontalDivider(color = Edge)
        Metadata(R.string.type, stringResource(if (variant.stage.isBlank()) R.string.unknown else variant.kind.label))
        Metadata(R.string.requirements, variant.requirements.ifBlank { stringResource(R.string.unknown) })
        if (variant.knownAbis.isNotEmpty()) Metadata(R.string.architectures, variant.knownAbis.joinToString(" · "))
        Metadata(R.string.compatibility, stringResource(R.string.odin_unknown))
        Metadata(R.string.controller, controllerLabel(variant.controller))
        if (variant.evidence.isNotEmpty()) {
            Text(stringResource(R.string.evidence), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                variant.evidence.distinct().forEachIndexed { index, url ->
                    Action(stringResource(R.string.evidence_link, index + 1)) { context.openUrl(url) }
                }
            }
        }
    }
    chooser?.let { release ->
        val options = release.compatibleAssets(abis, track?.filter ?: variant.filter)
        ApkPicker(variant, release, options, track?.assetName.orEmpty(), { chooser = null }) { apk ->
            chooser = null
            model.download(variant, release, apk)
        }
    }
    if (confirmingUnfollow) AlertDialog(onDismissRequest = { confirmingUnfollow = false },
        title = { Text(stringResource(R.string.unfollow)) },
        text = { Text(stringResource(R.string.unfollow_note)) },
        confirmButton = { Action(stringResource(R.string.unfollow), primary = true) {
            confirmingUnfollow = false; model.toggleFollow(variant)
            if (variant.stage.isBlank()) model.closeDetail()
        } },
        dismissButton = { Action(stringResource(R.string.cancel)) { confirmingUnfollow = false } })
    if (confirmManual) AlertDialog(onDismissRequest = { confirmManual = false },
        title = { Text(stringResource(R.string.setup_manual)) },
        text = { Text(stringResource(R.string.manual_note)) },
        confirmButton = { Action(stringResource(R.string.download_apk)) {
            confirmManual = false
            if (current != null) chooser = current else model.check(variant, false, true) { chooser = it }
        } },
        dismissButton = { Action(stringResource(R.string.guide), primary = true) { confirmManual = false; context.openUrl(variant.guide) } })
}

@Composable
private fun Metadata(label: Int, value: String) {
    Column {
        Note(stringResource(label), Mint)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApkPicker(variant: Variant, release: Release, options: List<Asset>, prior: String,
                      close: () -> Unit, download: (Asset) -> Unit) {
    var selected by remember(release, options) {
        mutableStateOf(preferredAsset(options, prior) ?: options.firstOrNull()?.takeIf { first ->
            options.count { it.abi == first.abi } == 1
        })
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.download_apk)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Note("${variant.repository} · ${release.tag}")
                Text(variant.gameFiles.ifBlank { stringResource(R.string.files_unknown) }, style = MaterialTheme.typography.bodyMedium)
                if (options.isEmpty()) Note(stringResource(R.string.no_compatible_apk))
                options.forEach { apk ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(apk == selected, { selected = apk }, modifier = Modifier.focusRing())
                        Column {
                            Text(apk.name, style = MaterialTheme.typography.bodyMedium)
                            Note(listOfNotNull(apk.abi, readableBytes(apk.bytes)).joinToString(" · "))
                        }
                    }
                }
            }
        },
        confirmButton = { Action(stringResource(R.string.download), primary = true, enabled = selected != null) { selected?.let(download) } },
        dismissButton = { Action(stringResource(R.string.cancel), click = close) })
}

@Composable
private fun TransferCard(transfer: Transfer, progress: DownloadProgress?, model: PortarioViewModel, install: (Transfer) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(transfer.asset)
        when (transfer.status) {
            "downloading" -> {
                Note(stringResource(R.string.downloading) + " · " + readableBytes(progress?.bytes ?: 0) +
                    " / " + readableBytes(progress?.total ?: transfer.bytes))
                if ((progress?.total ?: 0) > 0) LinearProgressIndicator(progress = {
                    ((progress?.bytes ?: 0).toFloat() / (progress?.total ?: 1)).coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            "installing" -> Note(stringResource(R.string.installing))
            "ready" -> Action(stringResource(R.string.install), primary = true) { install(transfer) }
            else -> Note(stringResource(R.string.download_failed), MaterialTheme.colorScheme.error)
        }
        if (transfer.message.isNotBlank()) Note(transfer.message, MaterialTheme.colorScheme.error)
        if (transfer.status != "installing") Action(stringResource(if (transfer.status == "downloading") R.string.cancel else R.string.remove_apk)) {
            model.cancel(transfer)
        }
    }
}

@Composable
private fun readableBytes(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))
