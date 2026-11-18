package br.com.amaral.portario

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent as NativeKey
import android.view.MotionEvent
import android.view.InputDevice
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<PortarioViewModel>()
    private var lastStick = 0L
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF151A24.toInt())
        )
        installControllerInput()
        handleIncoming(intent)
        setContent {
            PortarioTheme {
                PortarioScreen(model, ::askNotifications, ::installApk, ::openPackage)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.app.downloads.approval.collect { pending ->
                    if (pending != null) {
                        model.app.downloads.approval.value = null
                        if (runCatching { startActivity(pending.intent) }.isFailure && pending.session >= 0) {
                            runCatching { packageManager.packageInstaller.abandonSession(pending.session) }
                            model.notices.tryEmit(R.string.install_failed)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); model.onForeground() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                model.navigate(Destination.ADD)
                model.edit(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                if (model.input.isNotBlank()) model.inspect()
            }
            OPEN_LIBRARY -> model.navigate(Destination.LIBRARY)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun installApk(transfer: Transfer) {
        if (packageManager.canRequestPackageInstalls()) model.install(transfer)
        else {
            model.notices.tryEmit(R.string.install_permission)
            openIntent(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }

    private fun openPackage(packageId: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageId)
        if (launch == null) model.notices.tryEmit(R.string.launch_failed) else openIntent(launch)
    }

    private fun openIntent(intent: Intent) {
        if (runCatching { startActivity(intent) }.isFailure) model.notices.tryEmit(R.string.launch_failed)
    }

    private fun installControllerInput() {
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: NativeKey): Boolean {
                return when (event.keyCode) {
                    NativeKey.KEYCODE_BUTTON_A -> original.dispatchKeyEvent(NativeKey(event.downTime,
                        event.eventTime, event.action, NativeKey.KEYCODE_DPAD_CENTER, event.repeatCount))
                    NativeKey.KEYCODE_BUTTON_B -> {
                        if (event.action == NativeKey.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                        true
                    }
                    else -> original.dispatchKeyEvent(event)
                }
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_MOVE && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
                    val x = event.getAxisValue(MotionEvent.AXIS_X)
                    val y = event.getAxisValue(MotionEvent.AXIS_Y)
                    val code = when {
                        kotlin.math.abs(x) > kotlin.math.abs(y) && x > .55f -> NativeKey.KEYCODE_DPAD_RIGHT
                        kotlin.math.abs(x) > kotlin.math.abs(y) && x < -.55f -> NativeKey.KEYCODE_DPAD_LEFT
                        y > .55f -> NativeKey.KEYCODE_DPAD_DOWN
                        y < -.55f -> NativeKey.KEYCODE_DPAD_UP
                        else -> 0
                    }
                    if (code != 0) {
                        if (event.eventTime - lastStick >= 180) {
                            lastStick = event.eventTime
                            original.dispatchKeyEvent(NativeKey(NativeKey.ACTION_DOWN, code))
                            original.dispatchKeyEvent(NativeKey(NativeKey.ACTION_UP, code))
                        }
                        return true
                    }
                }
                return original.dispatchGenericMotionEvent(event)
            }
        }
    }

    companion object { const val OPEN_LIBRARY = "br.com.amaral.portario.OPEN_LIBRARY" }
}

internal val Night = Color(0xFF0B0D12)
internal val Panel = Color(0xFF151A24)
internal val PanelRaised = Color(0xFF202737)
internal val Mint = Color(0xFF5CF2C2)
internal val Violet = Color(0xFFA78BFA)
internal val Ink = Color(0xFFF1F5F9)
internal val Muted = Color(0xFFA9B4C5)
internal val Edge = Color(0xFF333D4C)
private val titleFont = FontFamily(Font(R.font.barlow_condensed, FontWeight.SemiBold))
private val bodyFont = FontFamily(Font(R.font.inter))

@Composable
private fun PortarioTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Mint, onPrimary = Night, secondary = Violet, onSecondary = Night,
            background = Night, surface = Panel, surfaceVariant = PanelRaised, onBackground = Ink, onSurface = Ink,
            onSurfaceVariant = Muted, outline = Edge, error = Color(0xFFFFA5AC)),
        typography = base.copy(
            displaySmall = TextStyle(fontFamily = titleFont, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 46.sp),
            headlineMedium = TextStyle(fontFamily = titleFont, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp),
            titleLarge = TextStyle(fontFamily = titleFont, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 29.sp),
            titleMedium = base.titleMedium.copy(fontFamily = bodyFont),
            bodyLarge = base.bodyLarge.copy(fontFamily = bodyFont),
            bodyMedium = base.bodyMedium.copy(fontFamily = bodyFont),
            bodySmall = base.bodySmall.copy(fontFamily = bodyFont),
            labelLarge = base.labelLarge.copy(fontFamily = bodyFont),
            labelMedium = base.labelMedium.copy(fontFamily = bodyFont)
        ), content = content
    )
}

internal fun Modifier.focusRing(shape: RoundedCornerShape = RoundedCornerShape(14.dp)): Modifier = composedFocusRing(shape)

private fun Modifier.composedFocusRing(shape: RoundedCornerShape) = composed {
    var focused by remember { mutableStateOf(false) }
    this.onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 2.dp else 0.dp, if (focused) Mint else Color.Transparent, shape)
}

@Composable
internal fun Note(text: String, color: Color = Muted, modifier: Modifier = Modifier) {
    Text(text, modifier, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun Action(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    click: () -> Unit
) {
    val outlined = modifier.focusRing(RoundedCornerShape(50))
    if (primary) Button(click, modifier = outlined, enabled = enabled) { Text(label) }
    else OutlinedButton(click, modifier = outlined, enabled = enabled) { Text(label) }
}

internal fun Context.openUrl(url: String) {
    if (url.startsWith("https://")) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

internal val Destination.label get() = when (this) {
    Destination.EXPLORE -> R.string.explore
    Destination.LIBRARY -> R.string.library
    Destination.ADD -> R.string.add
}

@Composable
private fun PortarioScreen(model: PortarioViewModel, askNotifications: () -> Unit,
                           install: (Transfer) -> Unit, openPackage: (String) -> Unit) {
    val context = LocalContext.current
    val games by model.games.collectAsStateWithLifecycle()
    val tracks by model.tracks.collectAsStateWithLifecycle()
    val caches by model.caches.collectAsStateWithLifecycle()
    val transfers by model.transfers.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val periodic by model.periodic.collectAsStateWithLifecycle()
    val toast = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    val libraryList = rememberLazyListState()
    var search by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(listOf("", "", "", "", "")) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(games, search, filters) {
        games.filter { matchesFilters(it, search, filters) }
    }
    val detail = games.find { it.id == model.selectedGame }
    val variant = detail?.variants?.find { it.id == model.selectedVariant } ?: detail?.variants?.firstOrNull()
    val trackById = tracks.associateBy { it.id }
    val transferById = transfers.associateBy { it.variant }
    LaunchedEffect(model) { model.notices.collect { toast.showSnackbar(context.getString(it)) } }
    BackHandler(detail != null || model.destination != Destination.EXPLORE) {
        if (detail != null) model.closeDetail() else model.navigate(Destination.EXPLORE)
    }
    Scaffold(
        containerColor = Night,
        snackbarHost = { SnackbarHost(toast) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Destination.entries.forEach { route ->
                    NavigationBarItem(
                        selected = route == model.destination, onClick = { model.navigate(route) },
                        modifier = Modifier.focusRing(),
                        icon = { NavGlyph(route, if (route == model.destination) Mint else Muted) },
                        label = { Text(stringResource(route.label)) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = PanelRaised, selectedTextColor = Ink)
                    )
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 680.dp
            val showList = detail == null || wide
            Row(Modifier.fillMaxSize()) {
                if (showList) Column(Modifier.weight(if (wide && detail != null) 0.52f else 1f)) {
                    Brand(compact = wide)
                    when (model.destination) {
                        Destination.EXPLORE -> Explore(filtered, search, { search = it }, filters.count { it.isNotEmpty() },
                            { filterOpen = true }, list, model.lastGame.takeIf { detail == null }, model::show)
                        Destination.LIBRARY -> LibraryPage(model, games, tracks, caches, transferById, periodic, libraryList,
                            askNotifications)
                        Destination.ADD -> AddPage(model)
                    }
                }
                if (detail != null && variant != null) {
                    if (wide) VerticalDivider(color = Edge)
                    val current = trackById[variant.id]
                    DetailPage(detail, variant, current, caches[ReleaseRepository.cacheKey(variant.withTrack(current))],
                        transferById[variant.id], progress[variant.id], model, install, openPackage,
                        Modifier.weight(if (wide) 0.48f else 1f), compact = wide)
                }
            }
        }
    }
    if (filterOpen) FiltersDialog(games, filters, { filters = it }, { filterOpen = false })
}

@Composable
private fun Brand(compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(if (compact) 43.dp else 49.dp).clip(RoundedCornerShape(13.dp)).background(Panel),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_portal), null, Modifier.size(if (compact) 57.dp else 65.dp), tint = Color.Unspecified)
        }
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
                fontSize = if (compact) 34.sp else 42.sp, color = Ink)
            if (!compact) Note(stringResource(R.string.subtitle))
        }
    }
}

@Composable
private fun NavGlyph(route: Destination, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val s = size.width
        val w = 1.8.dp.toPx()
        when (route) {
            Destination.EXPLORE -> {
                drawCircle(color, s * .39f, style = Stroke(w))
                drawPath(Path().apply { moveTo(s*.64f,s*.36f); lineTo(s*.55f,s*.56f); lineTo(s*.36f,s*.65f); lineTo(s*.45f,s*.45f); close() }, color)
            }
            Destination.LIBRARY -> for (y in listOf(.17f,.42f,.67f)) {
                drawRoundRect(color, Offset(s*.13f,s*y), Size(s*.74f,s*.12f), style=Stroke(w))
            }
            Destination.ADD -> {
                drawLine(color, Offset(s*.2f,s*.5f), Offset(s*.8f,s*.5f),w)
                drawLine(color, Offset(s*.5f,s*.2f), Offset(s*.5f,s*.8f),w)
            }
        }
    }
}

@Composable
private fun Explore(games: List<Game>, search: String, edit: (String) -> Unit, filters: Int, filter: () -> Unit,
                    list: LazyListState, restore: String?, open: (Game, Variant) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(restore) {
        if (restore != null && games.any { it.id == restore }) {
            val index = games.indexOfFirst { it.id == restore }
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            delay(60)
            runCatching { focus.requestFocus() }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(search, edit, Modifier.weight(1f).focusRing(),
            singleLine = true, label = { Text(stringResource(R.string.search)) },
            trailingIcon = if (search.isNotBlank()) ({ TextButton({ edit("") }) { Text("×") } }) else null)
        Action(if (filters == 0) stringResource(R.string.filters) else stringResource(R.string.filters_count, filters), click = filter)
    }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 3.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.catalog_title), style = MaterialTheme.typography.titleLarge, color = Ink)
        Note(stringResource(R.string.catalog_count, games.size))
    }
    if (games.isEmpty()) Note(stringResource(R.string.no_results), modifier = Modifier.padding(20.dp))
    LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(games, key = { it.id }) { game ->
            GameRow(game, game.variants.first(),
                if (game.id == restore) Modifier.focusRequester(focus) else Modifier,
                if (game.variants.size > 1) stringResource(R.string.variant_count, game.variants.size) else null) {
                keyboard?.hide()
                focusManager.clearFocus()
                open(game, game.variants.first())
            }
        }
    }
}

@Composable
internal fun GameRow(game: Game, variant: Variant, modifier: Modifier = Modifier, status: String? = null, open: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Row(modifier.fillMaxWidth().focusRing(shape).clip(shape).background(Panel).clickable(onClick = open)
        .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(game.genre, Modifier.size(width = 74.dp, height = 65.dp).clip(RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val line = listOf(game.platform, game.genre).filter(String::isNotBlank).joinToString("  ·  ")
            if (line.isNotBlank()) Text(line, color = Mint, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(variant.name.takeUnless { it.equals(game.title, true) }.orEmpty(), variant.stage)
                .filter(String::isNotBlank).joinToString("  ·  ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status != null) Note(status, Violet, Modifier.widthIn(max = 77.dp))
        Text("›", Modifier.padding(horizontal = 5.dp), color = Mint, fontSize = 25.sp)
    }
}

@Composable
internal fun Cover(genre: String, modifier: Modifier) {
    val tone = when (normalized(genre)) {
        "corrida" -> Color(0xFFFF8460)
        "rpg" -> Color(0xFFB097FF)
        "aventura" -> Color(0xFF5CF2C2)
        "estrategia" -> Color(0xFFFFC869)
        "plataforma" -> Color(0xFF79B2FF)
        "terror" -> Color(0xFFFF819C)
        else -> Color(0xFF82A1F6)
    }
    Canvas(modifier.background(Brush.linearGradient(listOf(tone.copy(alpha = .28f), Color(0xFF101422))))) {
        val x = size.width
        val y = size.height
        fun line(ax: Float, ay: Float, bx: Float, by: Float, alpha: Float = .4f) =
            drawLine(tone.copy(alpha = alpha), Offset(x*ax, y*ay), Offset(x*bx, y*by), 1.2.dp.toPx())
        if (genre == "Corrida") {
            for (i in 0..7) line(i*.18f-.4f, 1f, i*.16f+.3f, 0f, (i+2)*.09f)
            line(0f,.67f,1f,.36f,.9f)
        } else if (genre == "RPG") {
            for (i in 0..6) {
                val reach = (.08f + i*.055f)
                drawPath(Path().apply {
                    moveTo(x*.51f,y*(.51f-reach)); lineTo(x*(.51f+reach),y*.51f)
                    lineTo(x*.51f,y*(.51f+reach)); lineTo(x*(.51f-reach),y*.51f); close()
                }, tone.copy(alpha = .85f-i*.1f), style = Stroke(1.2.dp.toPx()))
            }
        } else {
            drawCircle(tone.copy(alpha = .32f), y*.14f, Offset(x*.74f,y*.28f))
            drawCircle(tone.copy(alpha = .9f), y*.14f, Offset(x*.74f,y*.28f), style=Stroke(1.dp.toPx()))
            for (i in 0..5) {
                val shift = i*.07f
                line(-.12f,.88f+shift,.48f,.31f+shift, .9f-i*.12f)
                line(.48f,.31f+shift,1.03f,.83f+shift, .9f-i*.12f)
            }
        }
    }
}
