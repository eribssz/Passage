package dev.eribssz.passage.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eribssz.passage.Prefs
import dev.eribssz.passage.Profile
import dev.eribssz.passage.Rate
import dev.eribssz.passage.Throughput
import dev.eribssz.passage.Traffic
import dev.eribssz.passage.VpnController
import dev.eribssz.passage.VpnState
import dev.eribssz.passage.core.CertInfo
import dev.eribssz.passage.core.Endpoint
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Mono = FontFamily.Monospace

private fun stateColor(s: VpnState) = when (s) {
    is VpnState.Connected -> Pal.Mint
    is VpnState.Busy, is VpnState.NeedsCert, is VpnState.NeedsCode -> Pal.Amber
    is VpnState.Idle -> if (s.isError) Pal.Coral else Pal.Idle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassageApp(
    prefs: Prefs, onConnect: (String) -> Unit, onDisconnect: () -> Unit, onAddTile: (() -> Unit)? = null,
    onExport: () -> Unit = {}, onImport: () -> Unit = {}, configRev: Int = 0,
) {
    PassageTheme {
        val state by VpnController.state.collectAsStateWithLifecycle()
        val traffic by VpnController.traffic.collectAsStateWithLifecycle()
        val throughput by VpnController.throughput.collectAsStateWithLifecycle()
        var profiles by remember { mutableStateOf(prefs.profiles()) }
        var profile by remember { mutableStateOf(prefs.active()) }
        var password by remember(profile.id) { mutableStateOf(prefs.loadPassword(profile.id) ?: "") }
        var showGateways by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Profile?>(null) }
        var showLog by remember { mutableStateOf(false) }
        var pickAddress by remember { mutableStateOf(false) }

        fun reload() { profiles = prefs.profiles(); profile = prefs.active() }
        LaunchedEffect(configRev) { if (configRev > 0) reload() }

        val color by animateColorAsState(stateColor(state), tween(600), label = "c")

        fun connect() {
            if (profile.isNone) { editing = Profile(); return }
            if (profile.servers.isEmpty()) { editing = profile; return }
            if (password.isEmpty()) {
                VpnController.set(VpnState.Idle("Enter your password", true)); return
            }
            prefs.savePassword(profile.id, if (profile.rememberPassword) password else null)
            onConnect(password)
        }

        val onOrb = {
            when (state) {
                is VpnState.Idle -> connect()
                else -> onDisconnect()
            }
        }
        val header: @Composable () -> Unit = {
            Header(onLog = { showLog = true }, onSettings = { reload(); showGateways = true })
        }

        /** The cards under (portrait) or beside (landscape) the orb. */
        @Composable
        fun Body(chartHeight: Dp?) {
            AnimatedContent(
                targetState = when (state) {
                    is VpnState.Connected -> 2
                    is VpnState.Idle -> 0
                    else -> 1
                },
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "body",
            ) { which ->
                when (which) {
                    0 -> if (profile.isNone) WelcomeCard(onAdd = { editing = Profile() }, onImport = onImport)
                    else SignInCard(profile, profiles.size, password, { password = it },
                        onRemember = { r ->
                            prefs.save(profile.copy(rememberPassword = r)); reload()
                        },
                        onPickGateway = { reload(); showGateways = true },
                        onPickAddress = { reload(); pickAddress = true },
                        onConnect = ::connect)
                    1 -> ProgressCard(state as? VpnState.Busy, profile.servers.size, onCancel = onDisconnect)
                    else -> (state as? VpnState.Connected)?.let {
                        if (chartHeight == null) ConnectedCard(it, traffic, onDisconnect)
                        else ConnectedWide(it, traffic, throughput, chartHeight, onDisconnect)
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Pal.Bg)
                .drawBehind {
                    // Landscape: the glow sits behind the orb in the left half.
                    val wide = size.width > size.height
                    drawRect(Brush.radialGradient(
                        listOf(color.copy(alpha = 0.16f), Color.Transparent),
                        center = if (wide) Offset(size.width * 0.25f, size.height * 0.45f)
                        else Offset(size.width / 2, size.height * 0.2f),
                        radius = size.maxDimension * (if (wide) 0.4f else 0.6f),
                    ))
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                if (maxWidth > maxHeight) {
                    // Landscape (tablets and phones): orb on the left, everything else on the right.
                    val area = maxHeight - 64.dp
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(horizontal = 24.dp)) { header() }
                        Row(Modifier.fillMaxSize()) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                                val h = maxHeight
                                Column(
                                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                        .heightIn(min = h).padding(bottom = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Orb(state, color, diameter = (area - 120.dp).coerceIn(150.dp, 360.dp), onClick = onOrb)
                                    StatusText(state, color, hasGateway = !profile.isNone)
                                    (state as? VpnState.Connected)?.let { SessionClock(it.since) }
                                }
                            }
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                                    .padding(start = 8.dp, end = 32.dp, top = 4.dp, bottom = 24.dp),
                            ) {
                                Body(chartHeight = (area * 0.3f).coerceIn(84.dp, 240.dp))
                            }
                        }
                    }
                } else Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                        header()
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Orb(state, color, onClick = onOrb)
                        }
                        StatusText(state, color, hasGateway = !profile.isNone)
                        Spacer(Modifier.height(28.dp))
                        Body(chartHeight = null)
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }

            (state as? VpnState.NeedsCert)?.let { s ->
                CertSheet(s.info,
                    onTrust = {
                        prefs.addPin(profile.id, s.info.endpoint, s.info.sha256)
                        reload()
                        VpnController.log("Trusted certificate ${s.info.sha256.take(16)}… for ${s.info.endpoint}")
                        connect()
                    },
                    onCancel = { VpnController.set(VpnState.Idle()) })
            }
            (state as? VpnState.NeedsCode)?.let { s ->
                CodeDialog(s.prompt,
                    onSubmit = { VpnController.codeQueue.offer(it) },
                    onCancel = { VpnController.codeQueue.offer(""); onDisconnect() })
            }
            if (showGateways) {
                GatewaysSheet(profiles, profile.id, locked = state !is VpnState.Idle, onAddTile = onAddTile,
                    onExport = { showGateways = false; onExport() }, onImport = { showGateways = false; onImport() },
                    onDismiss = { showGateways = false },
                    onSelect = { prefs.setActive(it.id); reload(); showGateways = false },
                    onEdit = { showGateways = false; editing = it },
                    onAdd = { showGateways = false; editing = Profile(name = "", servers = emptyList()) })
            }
            editing?.let { e ->
                EditorSheet(e, isNew = profiles.none { it.id == e.id }, canDelete = profiles.size > 1 && profiles.any { it.id == e.id },
                    onDismiss = { editing = null },
                    onSave = { p ->
                        prefs.save(p)
                        if (profiles.none { it.id == p.id }) prefs.setActive(p.id)
                        reload(); editing = null
                    },
                    onForgetCerts = { prefs.clearPins(e.id); reload(); editing = profiles.firstOrNull { it.id == e.id } },
                    onDelete = { prefs.delete(e.id); reload(); editing = null })
            }
            if (pickAddress) {
                AddressSheet(profile, onDismiss = { pickAddress = false }, onPick = { key ->
                    prefs.setPreferred(profile.id, key); reload(); pickAddress = false
                })
            }
            if (showLog) LogSheet { showLog = false }
        }
    }
}


@Composable
private fun Header(onLog: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("passage", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onLog) { Icon(Icons.Default.List, "Activity", tint = Pal.Muted) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Gateway settings", tint = Pal.Muted) }
    }
}

/** The "Route" P from the app icon, in its 108-unit icon grid. Drawn from the start node to the end node. */
private const val P_PATH = "M38,76 V40 A4,4 0 0,1 42,36 H58 A14,14 0 0,1 58,64 H50"
private val P_START = Offset(38f, 78f)
private val P_END = Offset(47f, 64f)
private val P_CENTER = Offset(51.5f, 60f)

private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

@Composable
private fun Orb(state: VpnState, color: Color, diameter: Dp = 240.dp, onClick: () -> Unit) {
    val busy = state is VpnState.Busy || state is VpnState.NeedsCert || state is VpnState.NeedsCode
    val connected = state is VpnState.Connected
    val inf = rememberInfiniteTransition(label = "orb")
    val pulse by inf.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "s")
    val glow by animateFloatAsState(if (connected) 0.42f else if (busy) 0.3f else 0.1f, tween(700), label = "g")
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")

    // Connecting: the line travels from the start node to the end node, over and over.
    val cycle = remember { Animatable(0f) }
    val lastDraw = remember { floatArrayOf(0f) }
    LaunchedEffect(busy) {
        if (busy) {
            cycle.snapTo(0f)
            while (true) { cycle.animateTo(1f, tween(1325, easing = LinearEasing)); cycle.snapTo(0f) }
        }
    }
    // Connected: finish the line from wherever it was, then pop the end node.
    val finish = remember { Animatable(if (connected) 1f else 0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(connected) {
        if (connected) {
            finish.snapTo(lastDraw[0])
            pop.snapTo(0f)
            launch { finish.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
            pop.animateTo(1f, tween(750))
        } else finish.snapTo(0f)
    }

    val glyph = remember { PathParser().parsePathString(P_PATH).toPath() }
    val measure = remember { PathMeasure().apply { setPath(glyph, false) } }
    val part = remember { Path() }

    Box(
        Modifier.size(diameter).scale(press).clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val k = if (busy || connected) pulse else 1f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow * k), Color.Transparent), center, r), r)
            val ringR = r * 0.7f
            val sw = 3.dp.toPx()
            drawCircle(color.copy(alpha = 0.16f), ringR, style = Stroke(sw))
            val tl = Offset(center.x - ringR, center.y - ringR)
            val sz = Size(ringR * 2, ringR * 2)
            if (busy) drawArc(color, spin, 110f, false, tl, sz, style = Stroke(sw * 1.4f, cap = StrokeCap.Round))
            if (connected) drawCircle(color, ringR, style = Stroke(sw * 1.4f))
            val discTop = Color(0xFF18233B); val discBottom = Color(0xFF0C1322)
            drawCircle(Brush.verticalGradient(listOf(discTop, discBottom),
                startY = center.y - r * 0.6f, endY = center.y + r * 0.6f), r * 0.6f)
            drawCircle(Color.White.copy(alpha = 0.04f), r * 0.6f, style = Stroke(1.dp.toPx()))

            // How much of the P is lit, and how bright.
            val (draw, fade) = when {
                connected -> finish.value to 1f
                busy -> {
                    val c = cycle.value
                    smooth(c / 0.72f) to (1f - ((c - 0.79f) / 0.19f).coerceIn(0f, 1f))
                }
                else -> 0f to 0f
            }
            if (busy) lastDraw[0] = draw * fade
            val len = measure.length
            val s = r * 0.0111f
            withTransform({
                translate(center.x - P_CENTER.x * s, center.y - P_CENTER.y * s)
                scale(s, s, Offset.Zero)
            }) {
                val line = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(glyph, color.copy(alpha = if (busy || connected) 0.22f else 1f), style = line)
                if (draw > 0.002f && fade > 0f) {
                    part.reset()
                    measure.getSegment(0f, len * draw, part, true)
                    drawPath(part, color.copy(alpha = fade), style = line)
                    if (busy && draw < 0.985f) drawCircle(Color.White.copy(alpha = 0.85f * fade), 1.6f, measure.getPosition(len * draw))
                }
                // Start node: you. Ripples while connecting.
                drawCircle(Color(0xFF111A2D), 7f, P_START)
                drawCircle(color, 7f, P_START, style = Stroke(5f))
                if (busy) {
                    val rp = cycle.value
                    drawCircle(color.copy(alpha = 0.55f * (1 - rp)), 7f + 9f * rp, P_START, style = Stroke(2f))
                }
                // End node: the network. Lights when the line reaches it; pops on connect.
                val arrived = draw > 0.985f
                val p = pop.value
                val er = if (connected) 6f * (1 + 0.45f * sin(PI.toFloat() * p)) else if (arrived) 7.2f else 6f
                val ea = when { connected -> 1f; busy -> if (arrived) fade else 0.22f; else -> 1f }
                drawCircle(color.copy(alpha = ea), er, P_END)
                if (connected && p < 1f) drawCircle(color.copy(alpha = 0.6f * (1 - p)), 6f + 14f * p, P_END, style = Stroke(2f))
            }
        }
    }
}

@Composable
private fun StatusText(state: VpnState, color: Color, hasGateway: Boolean) {
    val (title, sub) = when (state) {
        is VpnState.Connected -> "Protected" to state.ip
        is VpnState.Busy -> "Connecting" to state.step
        is VpnState.NeedsCert -> "Check the gateway" to "Confirm its certificate"
        is VpnState.NeedsCode -> "Verification" to "Enter your code"
        is VpnState.Idle -> "Not connected" to (state.message ?: if (hasGateway) "Tap to connect" else "No gateway yet")
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Pal.Text)
        Spacer(Modifier.height(6.dp))
        val err = state is VpnState.Idle && state.isError
        Text(sub, color = if (err) Pal.Coral else if (state is VpnState.Connected) color else Pal.Muted,
            fontFamily = if (state is VpnState.Connected) Mono else null, fontSize = 15.sp)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pal.Surface)
            .border(1.dp, Pal.Line, RoundedCornerShape(22.dp)).padding(18.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pal.Faint)

@Composable
private fun SignInCard(
    p: Profile, profileCount: Int, password: String, onPassword: (String) -> Unit, onRemember: (Boolean) -> Unit,
    onPickGateway: () -> Unit, onPickAddress: () -> Unit, onConnect: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.clickable(onClick = onPickGateway)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(if (profileCount > 1) "Gateway · $profileCount saved" else "Gateway")
                    Spacer(Modifier.height(6.dp))
                    Text(p.name.ifBlank { "Untitled" }, color = Pal.Text, style = MaterialTheme.typography.titleMedium)
                    val fixed = p.fixed
                    val first = (fixed ?: p.primary)?.key ?: "No address yet"
                    val multi = p.servers.size > 1
                    Row(
                        Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = multi, onClick = onPickAddress).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(first, color = Pal.Muted, fontFamily = Mono, fontSize = 13.sp)
                        if (multi) {
                            Spacer(Modifier.width(8.dp))
                            Text(if (fixed != null) "Fixed" else "Auto · ${p.servers.size}",
                                color = if (fixed != null) Pal.Amber else Pal.Mint, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background((if (fixed != null) Pal.Amber else Pal.Mint).copy(alpha = 0.12f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp))
                            Icon(Icons.Default.ArrowDropDown, "Choose address", tint = Pal.Faint, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Text(if (p.trusted) "Trusted" else "First connection",
                    color = if (p.trusted) Pal.Mint else Pal.Amber, fontSize = 12.sp)
                Icon(Icons.Default.KeyboardArrowRight, null, tint = Pal.Faint)
            }
        }
        Panel {
            Label("Sign in as ${p.username.ifBlank { "—" }}")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password, onValueChange = onPassword, singleLine = true,
                placeholder = { Text("Password") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) {
                        Text(if (visible) "Hide" else "Show", color = Pal.Muted, fontSize = 13.sp)
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Pal.Mint, unfocusedBorderColor = Pal.Line,
                    focusedContainerColor = Pal.Bg, unfocusedContainerColor = Pal.Bg,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remember password", color = Pal.Text, fontSize = 14.sp)
                    Text("Needed for one-tap connect from Quick Settings", color = Pal.Faint, fontSize = 12.sp)
                }
                Switch(checked = p.rememberPassword, onCheckedChange = onRemember,
                    colors = SwitchDefaults.colors(checkedTrackColor = Pal.Mint, checkedThumbColor = Pal.Bg,
                        uncheckedTrackColor = Pal.Bg, uncheckedBorderColor = Pal.Line, uncheckedThumbColor = Pal.Faint))
            }
        }
        Button(
            onClick = onConnect, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pal.Mint, contentColor = Color(0xFF04140D)),
        ) { Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun WelcomeCard(onAdd: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel {
            Label("Get started")
            Spacer(Modifier.height(8.dp))
            Text("Add your FortiGate SSL-VPN gateway", color = Pal.Text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("You'll need its address (IP:port) and your username. Or import a config exported from another phone.",
                color = Pal.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pal.Mint, contentColor = Color(0xFF04140D))) {
            Text("Add gateway", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Line)) {
            Text("Import config", color = Pal.Muted)
        }
    }
}

private val steps = listOf("Signing in", "Getting tunnel settings", "Opening tunnel")

@Composable
private fun ProgressCard(busy: VpnState.Busy?, addressCount: Int, onCancel: () -> Unit) {
    val idx = when (busy?.step) {
        "Getting tunnel settings" -> 1
        "Opening tunnel" -> 2
        else -> 0
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel {
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    val c = when { i < idx -> Pal.Mint; i == idx -> Pal.Amber; else -> Pal.Line }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(14.dp))
                    Text(if (i == 0 && busy != null && busy.step !in steps && busy.step != "Starting") busy.step else s,
                        color = if (i <= idx) Pal.Text else Pal.Faint, fontSize = 15.sp)
                    if (i < idx) { Spacer(Modifier.weight(1f)); Text("done", color = Pal.Mint, fontSize = 12.sp) }
                }
            }
            busy?.endpoint?.let { ep ->
                HorizontalDivider(color = Pal.Line, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("via", color = Pal.Faint, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(ep, color = Pal.Muted, fontFamily = Mono, fontSize = 13.sp)
                    if (addressCount > 1) {
                        Spacer(Modifier.weight(1f))
                        Text("same IP first, then the next", color = Pal.Faint, fontSize = 12.sp)
                    }
                }
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Line)) {
            Text("Cancel", color = Pal.Muted)
        }
    }
}

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
    else -> "%.2f GB".format(n / 1073741824.0)
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

@Composable
private fun ConnectedCard(c: VpnState.Connected, t: Traffic, onDisconnect: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var showRoutes by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Session", duration(now - c.since), Modifier.weight(1f))
            Stat("Down", bytes(t.down), Modifier.weight(1f))
            Stat("Up", bytes(t.up), Modifier.weight(1f))
        }
        Panel {
            InfoRow("Gateway", c.profileName)
            InfoRow("Address", c.gateway + if (c.addressCount > 1) "  ·  ${if (c.fixed) "fixed" else "auto ${c.addressIndex + 1}/${c.addressCount}"}" else "", mono = true)
            InfoRow("Tunnel IP", c.ip, mono = true)
            Row(Modifier.fillMaxWidth().clickable(enabled = c.routes.isNotEmpty()) { showRoutes = !showRoutes }) {
                InfoRow("Routing", if (c.routes.isEmpty()) "All traffic" else "Split · ${c.routes.size} network${if (c.routes.size > 1) "s" else ""}")
            }
            if (showRoutes) c.routes.forEach {
                Text(it, color = Pal.Muted, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            if (c.dns.isNotEmpty()) InfoRow("DNS", c.dns.joinToString("  "), mono = true)
            InfoRow("FortiOS", c.fortiOs ?: "Not reported", dim = c.fortiOs == null)
            InfoRow("MTU", c.mtu.toString(), mono = true, last = true)
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Coral.copy(alpha = 0.5f))) {
            Text("Disconnect", color = Pal.Coral)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Pal.Surface)
        .border(1.dp, Pal.Line, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Label(label)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = Mono, maxLines = 1)
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, last: Boolean = false, dim: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.Muted, fontSize = 14.sp, modifier = Modifier.width(96.dp))
        Text(value, color = if (dim) Pal.Faint else Pal.Text, fontSize = 14.sp, fontFamily = if (mono) Mono else null,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    if (!last) HorizontalDivider(color = Pal.Line, thickness = 0.5.dp)
}

private fun rate(bps: Long): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024 * 1024 -> "%.0f KB/s".format(bps / 1024.0)
    else -> "%.2f MB/s".format(bps / 1048576.0)
}

/** Big session timer under the status in landscape. */
@Composable
private fun SessionClock(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Text(duration(now - since), color = Pal.Text.copy(alpha = 0.85f), fontFamily = Mono, fontSize = 24.sp,
        modifier = Modifier.padding(top = 10.dp))
}

/** Landscape "connected": live throughput graph with session totals, then the details as tiles. */
@Composable
private fun ConnectedWide(c: VpnState.Connected, t: Traffic, tp: Throughput, chartHeight: Dp, onDisconnect: () -> Unit) {
    var showRoutes by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(Pal.Surface).border(1.dp, Pal.Line, shape)) {
            BoxWithConstraints(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp)) {
                val rates: @Composable () -> Unit = {
                    Row {
                        Text("↓ ${rate(tp.current.down)}", color = Pal.Mint, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, softWrap = false)
                        Spacer(Modifier.width(14.dp))
                        Text("↑ ${rate(tp.current.up)}", color = Pal.Sky, fontFamily = Mono, fontSize = 14.sp, maxLines = 1, softWrap = false)
                    }
                }
                // Narrow cards (phones in landscape): rates go under the title instead of beside it.
                if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Label("Throughput · 60 s"); rates() }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Throughput · 60 s"); Spacer(Modifier.weight(1f)); rates()
                }
            }
            ThroughputChart(tp.samples, Modifier.fillMaxWidth().height(chartHeight).padding(top = 10.dp))
            HorizontalDivider(color = Pal.Line, thickness = 1.dp)
            Row(Modifier.height(IntrinsicSize.Min)) {
                FooterStat("Down", bytes(t.down), Modifier.weight(1f))
                VerticalDivider(color = Pal.Line, thickness = 1.dp)
                FooterStat("Up", bytes(t.up), Modifier.weight(1f))
                VerticalDivider(color = Pal.Line, thickness = 1.dp)
                FooterStat("Peak", rate(tp.peakDown), Modifier.weight(1f))
            }
        }
        val endpoint = when {
            c.addressCount <= 1 -> "Single"
            c.fixed -> "Fixed"
            else -> "Auto · ${c.addressIndex + 1} of ${c.addressCount}"
        }
        val routing = if (c.routes.isEmpty()) "All traffic" else "Split · ${c.routes.size} net${if (c.routes.size > 1) "s" else ""}"
        TileRow {
            Tile("Gateway", c.profileName, Modifier.weight(1f))
            Tile("Endpoint", endpoint, Modifier.weight(1f))
            Tile("Routing", routing, Modifier.weight(1f),
                onClick = if (c.routes.isNotEmpty()) ({ showRoutes = !showRoutes }) else null)
        }
        if (showRoutes) Panel {
            c.routes.forEach { Text(it, color = Pal.Muted, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        TileRow {
            Tile("Address", c.gateway, Modifier.weight(3f), mono = true, oneLine = true)
            Tile("Tunnel IP", c.ip, Modifier.weight(2f), mono = true, oneLine = true)
        }
        TileRow {
            Tile("DNS", if (c.dns.isEmpty()) "From gateway" else c.dns.joinToString("\n"), Modifier.weight(1f), mono = c.dns.isNotEmpty())
            Tile("FortiOS", c.fortiOs ?: "Not reported", Modifier.weight(1f), dim = c.fortiOs == null)
            Tile("MTU", c.mtu.toString(), Modifier.weight(1f), mono = true)
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Coral.copy(alpha = 0.5f))) {
            Text("Disconnect", color = Pal.Coral)
        }
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, mono: Boolean = false, dim: Boolean = false,
                 oneLine: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxHeight().clip(shape).background(Pal.Surface).border(1.dp, Pal.Line, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(label, color = Pal.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        if (oneLine) FitText(value, mono)
        else Text(value, color = if (dim) Pal.Faint else Pal.Text, fontSize = 14.sp, fontFamily = if (mono) Mono else null,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** One line that shrinks (down to 11sp) instead of wrapping, for addresses on narrow screens. */
@Composable
private fun FitText(value: String, mono: Boolean, start: Float = 14f, min: Float = 11f) {
    var size by remember(value) { mutableStateOf(start) }
    Text(value, color = Pal.Text, fontSize = size.sp, fontFamily = if (mono) Mono else null,
        maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        onTextLayout = { if (it.hasVisualOverflow && size > min) size -= 0.5f })
}

@Composable
private fun FooterStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Label(label)
        Spacer(Modifier.height(4.dp))
        FitText(value, mono = true, start = 16f, min = 11f)
    }
}

/** Download (filled, mint) and upload (line, blue) over the last minute; newest on the right. */
@Composable
private fun ThroughputChart(samples: List<Rate>, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            for (i in 1..2) drawLine(Pal.Line, Offset(0f, h * i / 3), Offset(w, h * i / 3), 1.dp.toPx())
            if (samples.size < 2) return@Canvas
            val top = maxOf(samples.maxOf { maxOf(it.down, it.up) }, 8L * 1024) * 1.15f
            val step = w / (Throughput.WINDOW - 1)
            val x0 = w - step * (samples.size - 1)
            val pad = 3.dp.toPx()
            fun y(v: Long) = h - pad - (h - 2 * pad) * (v / top)
            fun line(pick: (Rate) -> Long) = Path().apply {
                samples.forEachIndexed { i, r -> if (i == 0) moveTo(x0, y(pick(r))) else lineTo(x0 + step * i, y(pick(r))) }
            }
            val down = line { it.down }
            val area = Path().apply { addPath(down); lineTo(w, h); lineTo(x0, h); close() }
            drawPath(area, Pal.Mint.copy(alpha = 0.12f))
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(down, Pal.Mint, style = stroke)
            drawPath(line { it.up }, Pal.Sky, style = stroke)
        }
        if (samples.size < 2) Text("Waiting for traffic…", color = Pal.Faint, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Center))
    }
}

private fun cn(dn: String) = Regex("""CN=([^,]+)""").find(dn)?.groupValues?.get(1) ?: dn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CertSheet(info: CertInfo, onTrust: () -> Unit, onCancel: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet, containerColor = Pal.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            val warn = info.changed
            Text(if (warn) "Certificate changed" else "Trust this gateway?",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp),
                color = if (warn) Pal.Coral else Pal.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                if (warn) "This gateway is presenting a different certificate than the one you trusted. " +
                    "It may have been renewed by IT — or someone may be intercepting the connection. " +
                    "Only continue if you expected this."
                else "The gateway uses a certificate your phone can't verify (FortiClient warns about it every time). " +
                    "Check the fingerprint once; Passage will remember it and warn you if it ever changes.",
                color = Pal.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(18.dp))
            Label("SHA-256 fingerprint")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(14.dp)) {
                info.sha256.chunked(4).chunked(4).forEach { line ->
                    Text(line.joinToString("  "), fontFamily = Mono, color = if (warn) Pal.Coral else Pal.Mint, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            InfoRow("Issued to", cn(info.subject))
            InfoRow("Issued by", cn(info.issuer))
            InfoRow("Expires", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.notAfter)), last = true)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onTrust, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (warn) Pal.Coral else Pal.Mint, contentColor = Pal.Bg)) {
                Text(if (warn) "Trust new certificate" else "Trust and connect", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Pal.Muted) }
        }
    }
}

@Composable
private fun CodeDialog(prompt: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        containerColor = Pal.Surface,
        title = { Text("Verification code", color = Pal.Text) },
        text = {
            Column {
                Text(prompt, color = Pal.Muted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(code, { code = it.trim() }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.isNotEmpty()) onSubmit(code) }),
                    textStyle = LocalTextStyleMono(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(code) }, enabled = code.isNotEmpty()) { Text("Verify") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = Pal.Muted) } },
    )
}

@Composable
private fun LocalTextStyleMono() = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, fontSize = 20.sp, letterSpacing = 4.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewaysSheet(
    profiles: List<Profile>, activeId: String, locked: Boolean, onAddTile: (() -> Unit)?, onExport: () -> Unit, onImport: () -> Unit,
    onDismiss: () -> Unit, onSelect: (Profile) -> Unit, onEdit: (Profile) -> Unit, onAdd: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Pal.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gateways", style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp), color = Pal.Text)
            // A dropped tunnel reconnects to the active gateway with the session's password,
            // so switching or editing mid-session would reconnect somewhere else.
            if (locked) Text("Disconnect to switch, add or edit gateways.", color = Pal.Amber, fontSize = 13.sp)
            profiles.forEach { p ->
                val active = p.id == activeId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (active) Pal.Mint.copy(alpha = 0.08f) else Pal.Bg)
                        .border(1.dp, if (active) Pal.Mint.copy(alpha = 0.5f) else Pal.Line, RoundedCornerShape(16.dp))
                        .clickable(enabled = !locked) { onSelect(p) }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (locked) 16.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) Pal.Mint else Pal.Line))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { "Untitled" }, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(p.username.ifBlank { null },
                                if (p.servers.size == 1) p.servers[0].key else "${p.servers.size} addresses").joinToString(" · "),
                            color = Pal.Muted, fontSize = 13.sp, fontFamily = Mono)
                    }
                    if (!locked) IconButton(onClick = { onEdit(p) }) { Icon(Icons.Default.Edit, "Edit", tint = Pal.Muted) }
                }
            }
            if (!locked) OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Line)) {
                Icon(Icons.Default.Add, null, tint = Pal.Mint); Spacer(Modifier.width(8.dp))
                Text("Add gateway", color = Pal.Text)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onImport, enabled = !locked, modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Line)) {
                    Text("Import", color = Pal.Muted)
                }
                OutlinedButton(onClick = onExport, enabled = profiles.isNotEmpty(), modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Pal.Line)) {
                    Text("Export", color = Pal.Muted)
                }
            }
            Text("Export saves gateways, addresses and trusted certificates to a file. Passwords are never included.",
                color = Pal.Faint, fontSize = 12.sp)
            if (onAddTile != null) {
                TextButton(onClick = onAddTile, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Quick Settings tile", color = Pal.Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(
    p: Profile, isNew: Boolean, canDelete: Boolean, onDismiss: () -> Unit, onSave: (Profile) -> Unit,
    onForgetCerts: () -> Unit, onDelete: () -> Unit,
) {
    var name by remember(p.id) { mutableStateOf(p.name) }
    val servers = remember(p.id) { mutableStateListOf<Endpoint>().apply { addAll(p.servers) } }
    var newAddr by remember(p.id) { mutableStateOf("") }
    var addrError by remember(p.id) { mutableStateOf<String?>(null) }
    var user by remember(p.id) { mutableStateOf(p.username) }
    var realm by remember(p.id) { mutableStateOf(p.realm) }
    var confirmDelete by remember(p.id) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun addAddress(): Boolean {
        if (newAddr.isBlank()) return true
        val parts = newAddr.split(',', ';', ' ', '\n').filter { it.isNotBlank() }
        val parsed = parts.map { Endpoint.parse(it) }
        if (parsed.any { it == null }) { addrError = "Use IP:port, e.g. 203.0.113.10:4433"; return false }
        parsed.filterNotNull().forEach { if (it !in servers) servers += it }
        newAddr = ""; addrError = null
        return true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Pal.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isNew) "New gateway" else "Edit gateway",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp), color = Pal.Text)
            Field("Name", name) { name = it }

            Label("Addresses")
            Text("Tried in turn: the last one that worked goes first, and if it fails Passage moves to the next.",
                color = Pal.Faint, fontSize = 12.sp)
            servers.forEachIndexed { i, ep ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.Bg)
                    .padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Pal.Faint, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                    Text(ep.key, color = Pal.Text, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (p.pins.containsKey(ep.key)) Text("trusted", color = Pal.Mint, fontSize = 11.sp)
                    IconButton(onClick = { servers.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", tint = Pal.Faint) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newAddr, { newAddr = it; addrError = null }, singleLine = true,
                    placeholder = { Text(if (servers.isEmpty()) "IP:port" else "Add another IP:port") },
                    isError = addrError != null,
                    supportingText = addrError?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addAddress() }),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.Mint, unfocusedBorderColor = Pal.Line,
                        focusedContainerColor = Pal.Bg, unfocusedContainerColor = Pal.Bg),
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { addAddress() }, enabled = newAddr.isNotBlank(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Pal.Mint.copy(alpha = 0.15f))) {
                    Icon(Icons.Default.Add, "Add address", tint = Pal.Mint)
                }
            }

            Field("Username", user) { user = it.trim() }
            Field("Realm (optional)", realm) { realm = it.trim() }

            if (p.pins.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trusted certificates", color = Pal.Text, fontSize = 14.sp)
                        val distinct = p.pins.values.toSet().size
                        Text("${p.pins.size} address${if (p.pins.size > 1) "es" else ""} · $distinct certificate${if (distinct > 1) "s" else ""}",
                            color = Pal.Faint, fontSize = 12.sp)
                    }
                    TextButton(onClick = onForgetCerts) { Text("Forget", color = Pal.Coral) }
                }
            }

            Button(
                onClick = {
                    if (!addAddress()) return@Button
                    onSave(p.copy(name = name.ifBlank { servers.firstOrNull()?.host ?: "VPN" }, servers = servers.toList(),
                        username = user, realm = realm))
                },
                enabled = (servers.isNotEmpty() || newAddr.isNotBlank()) && user.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Pal.Mint, contentColor = Pal.Bg),
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            if (canDelete) {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (confirmDelete) "Tap again to delete" else "Delete gateway", color = Pal.Coral)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), mono: Boolean = false,
                  number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, fontSize = 15.sp) else MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.Mint, unfocusedBorderColor = Pal.Line,
            focusedLabelColor = Pal.Mint, focusedContainerColor = Pal.Bg, unfocusedContainerColor = Pal.Bg))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressSheet(p: Profile, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Pal.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Address", style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp), color = Pal.Text)
            @Composable
            fun Choice(selected: Boolean, title: String, sub: String?, mono: Boolean, onClick: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Pal.Mint.copy(alpha = 0.08f) else Pal.Bg)
                        .border(1.dp, if (selected) Pal.Mint.copy(alpha = 0.5f) else Pal.Line, RoundedCornerShape(16.dp))
                        .clickable(onClick = onClick).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (selected) Pal.Mint else Pal.Line))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Pal.Text, fontSize = 15.sp, fontFamily = if (mono) Mono else null,
                            fontWeight = if (mono) null else FontWeight.Medium)
                        if (sub != null) Text(sub, color = Pal.Muted, fontSize = 12.sp)
                    }
                }
            }
            Choice(p.fixed == null, "Automatic",
                "Starts with the last one that worked. If it fails, retries it, then moves to the next.", false) { onPick(null) }
            Label("Always use")
            p.servers.forEachIndexed { i, ep ->
                val tags = listOfNotNull(
                    if (i == p.lastGood.coerceIn(0, p.servers.size - 1)) "last used" else null,
                    if (p.pins.containsKey(ep.key)) "trusted" else null,
                ).joinToString(" · ").ifEmpty { null }
                Choice(p.fixed?.key == ep.key, ep.key, tags, true) { onPick(ep.key) }
            }
            Text("A fixed address never switches to another one, even if it's down.", color = Pal.Faint, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(onDismiss: () -> Unit) {
    val lines by VpnController.log.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pal.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", style = MaterialTheme.typography.displaySmall.copy(fontSize = 24.sp), color = Pal.Text,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { clip.setText(AnnotatedString(lines.joinToString("\n"))) }) { Text("Copy") }
            }
            Spacer(Modifier.height(8.dp))
            if (lines.isEmpty()) Text("Nothing yet.", color = Pal.Faint)
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                .clip(RoundedCornerShape(14.dp)).background(Pal.Bg).padding(12.dp)) {
                items(lines) { Text(it, fontFamily = Mono, fontSize = 12.sp, color = Pal.Muted, lineHeight = 17.sp) }
            }
        }
    }
}
