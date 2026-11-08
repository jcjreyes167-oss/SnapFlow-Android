package com.snapflow.app

import android.Manifest
import android.app.DownloadManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

private val Ink = Color(0xFF0A1019)
private val CardColor = Color(0xFF152131)
private val CardRaised = Color(0xFF1B2A3D)
private val Accent = Color(0xFFFFBB70)
private val Light = Color(0xFFF4F7FC)
private val Muted = Color(0xFF98AABE)
private val Good = Color(0xFF8BE5C0)

class MainActivity : ComponentActivity() {
    var incomingLink by mutableStateOf("")
    var shortcutRoute by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parseShare(intent)
        handleShortcut(intent)
        window.statusBarColor = android.graphics.Color.rgb(10, 16, 25)
        window.navigationBarColor = android.graphics.Color.rgb(16, 26, 38)
        setContent { SnapFlowApp(this) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseShare(intent)
        handleShortcut(intent)
    }
    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            "com.snapflow.app.action.PASTE_LINK" -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val raw = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                incomingLink = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value.orEmpty()
                shortcutRoute = "paste:" + System.nanoTime()
            }
            "com.snapflow.app.action.DOWNLOADS" -> shortcutRoute = "downloads:" + System.nanoTime()
        }
    }
    private fun parseShare(intent: Intent?) {
        val raw = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else intent?.dataString.orEmpty()
        val url = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value
            ?.trimEnd('.', ',', ')', ']') ?: ""
        if (url.isNotEmpty()) incomingLink = url
    }
}

private data class FoundMedia(val url: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapFlowApp(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf("") }
    var browserAddress by rememberSaveable { mutableStateOf("https://www.google.com") }
    var analysis by remember { mutableStateOf<MediaEngine.Analysis?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var audio by rememberSaveable { mutableStateOf(false) }
    var quality by rememberSaveable { mutableStateOf("auto") }
    var sheet by remember { mutableStateOf(false) }
    val detected = remember { mutableStateListOf<FoundMedia>() }
    var jobs by remember { mutableStateOf(JobStore.all(context)) }
    var updatingEngine by remember { mutableStateOf(false) }
    val helper: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val addMedia: (String, String) -> Unit = { url, title ->
        if (url.startsWith("https://", true) && detected.none { it.url == url } && detected.size < 50) {
            detected.add(FoundMedia(url, title.ifBlank { "Arquivo encontrado" }))
        }
    }
    val web = remember {
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    browserAddress = url.orEmpty()
                    view?.evaluateJavascript(
                        """(function(){let out=[]; document.querySelectorAll('video, video source, audio, audio source, a[href]').forEach(function(e){let u=e.src||e.href||'';if(!u.startsWith('https://'))return;let p=u.split('?')[0].toLowerCase();if(e.tagName==='A' && !/\\.(mp4|mp3|m4a|webm|m3u8|mpd)$/.test(p))return;out.push({url:u,label:(e.getAttribute('label')||e.getAttribute('res')||e.textContent||'Mídia').trim().slice(0,50)})});return JSON.stringify(out.slice(0,50));})()"""
                    ) { result ->
                        try {
                            val decoded = JSONArray("[" + result + "]").getString(0)
                            val values = JSONArray(decoded)
                            for (i in 0 until values.length()) {
                                val entry = values.getJSONObject(i)
                                addMedia(entry.optString("url"), entry.optString("label"))
                            }
                        } catch (_: Exception) {}
                    }
                }
                override fun onLoadResource(view: WebView?, url: String?) {
                    val link = url ?: return
                    val path = Uri.parse(link).path.orEmpty().lowercase()
                    if (listOf(".mp4", ".mp3", ".m4a", ".webm", ".m3u8", ".mpd", ".ogg").any { path.endsWith(it) }) {
                        addMedia(link, "Mídia carregada pela página")
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme.orEmpty()
                    return scheme != "https" && scheme != "http"
                }
            }
            setDownloadListener { url, _, _, mime, _ ->
                if (url.startsWith("https://", true)) {
                    activity.runOnUiThread {
                        addMedia(url, mime ?: "Arquivo oferecido pelo site")
                        sheet = true
                    }
                }
            }
            loadUrl("https://www.google.com")
        }
    }
    DisposableEffect(web) {
        onDispose { web.stopLoading(); web.destroy() }
    }
    LaunchedEffect(activity.shortcutRoute) {
        if (activity.shortcutRoute.startsWith("downloads:")) tab = 2
        if (activity.shortcutRoute.startsWith("paste:")) {
            tab = 0
            if (activity.incomingLink.isNotEmpty()) {
                source = activity.incomingLink
                analysis = null
            }
        }
    }
    LaunchedEffect(activity.incomingLink) {
        if (activity.incomingLink.isNotBlank()) {
            source = activity.incomingLink
            analysis = null
            tab = 0
        }
    }
    LaunchedEffect(tab) {
        if (tab == 2) {
            while (true) {
                withContext(Dispatchers.IO) { JobStore.refreshDirect(context) }
                jobs = JobStore.all(context)
                delay(1300L)
            }
        }
    }
    fun checkPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
        }
    }
    fun analyzeUrl(value: String) {
        val link = value.trim()
        if (!link.startsWith("https://", true)) {
            error = "Cole um link que comece com https://"
            return
        }
        analyzing = true
        error = ""
        analysis = null
        quality = "auto"
        audio = false
        scope.launch {
            try {
                analysis = withContext(Dispatchers.IO) {
                    withTimeout(65_000L) { MediaEngine.analyze(context, link) }
                }
            } catch (ex: Exception) {
                error = "Não foi possível analisar este endereço. " + (ex.message ?: "Fonte indisponível").take(150)
            } finally {
                analyzing = false
            }
        }
    }
    fun addDirect(url: String, title: String) {
        try {
            JobStore.addDirect(context, url, title)
            checkPermission()
            sheet = false
            tab = 2
            jobs = JobStore.all(context)
            helper("Download direto iniciado")
        } catch (ex: Exception) {
            helper("Erro: " + (ex.message ?: "Arquivo indisponível"))
        }
    }
    fun addExtract() {
        val info = analysis ?: return
        try {
            if (info.direct && !audio) {
                addDirect(source.trim(), info.title)
            } else {
                val job = JobStore.create(context, source.trim(), info.title, quality, audio)
                checkPermission()
                DownloadService.enqueue(context, job.id)
                jobs = JobStore.all(context)
                tab = 2
                helper("Adicionado à fila: " + info.title.take(35))
            }
        } catch (ex: Exception) {
            helper("Erro ao iniciar: " + (ex.message ?: "Indisponível"))
        }
    }
    fun retryDownload(job: DownloadJob) {
        try {
            if (job.kind == "direct") {
                JobStore.addDirect(context, job.url, job.title)
            } else {
                val created = JobStore.create(context, job.url, job.title, job.quality, job.mode == "audio")
                checkPermission()
                DownloadService.enqueue(context, created.id)
            }
            JobStore.remove(context, job.id)
            jobs = JobStore.all(context)
            helper("Nova tentativa iniciada")
        } catch (ex: Exception) {
            helper("Não foi possível tentar novamente: " + ex.message.orEmpty().take(90))
        }
    }
    fun updateExtractor() {
        if (updatingEngine) return
        updatingEngine = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { MediaEngine.updateEngine(context) }
                catch (ex: Exception) { "Não foi possível atualizar o motor: " + ex.message.orEmpty().take(100) }
            }
            updatingEngine = false
            helper(result)
        }
    }
    fun goBrowser(text: String) {
        val query = text.trim()
        val resolved = if (query.startsWith("http://") || query.startsWith("https://")) query
            else "https://www.google.com/search?q=" + Uri.encode(query)
        if (resolved.startsWith("https://") || resolved.startsWith("http://")) {
            browserAddress = resolved
            detected.clear()
            web.loadUrl(resolved)
        }
    }
    BackHandler(enabled = tab == 1) {
        if (web.canGoBack()) web.goBack() else tab = 0
    }
    val scheme = darkColorScheme(
        primary = Accent, onPrimary = Ink, background = Ink, onBackground = Light,
        surface = CardColor, onSurface = Light, secondary = Good, onSecondary = Ink,
        surfaceVariant = CardRaised, onSurfaceVariant = Muted, outline = Color(0xFF455568)
    )
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF111C29), tonalElevation = 0.dp) {
                    listOf(Triple("⌂", "Início", 0), Triple("◎", "Navegar", 1), Triple("⇩", "Downloads", 2))
                        .forEach { (glyph, title, id) ->
                            NavigationBarItem(
                                selected = tab == id,
                                onClick = { tab = id },
                                icon = { Text(glyph, fontSize = 22.sp, fontWeight = FontWeight.Bold) },
                                label = { Text(title, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ink, selectedTextColor = Accent,
                                    indicatorColor = Accent, unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.ic_app),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SnapFlow", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Light)
                        Text("Vídeos e áudio, sem complicação", fontSize = 11.sp, color = Muted)
                    }
                    Text("v3.2", fontSize = 11.sp, color = Muted)
                }
                when (tab) {
                    0 -> HomeScreen(
                        source = source, onSource = { source = it },
                        analysis = analysis, analyzing = analyzing, error = error,
                        audio = audio, onAudio = { audio = it },
                        quality = quality, onQuality = { quality = it },
                        onAnalyze = { analyzeUrl(source) },
                        onDownload = { addExtract() },
                        onPaste = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val content = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (content.isNotBlank()) {
                                source = Regex("https://[^\\s]+").find(content)?.value ?: content.trim()
                                analysis = null
                                error = ""
                            } else helper("A área de transferência está vazia")
                        },
                        onBrowser = { browserAddress = source.ifBlank { browserAddress }; goBrowser(browserAddress); tab = 1 },
                        onFiles = { tab = 2 },
                        onPin = {
                            val manager = context.getSystemService(ShortcutManager::class.java)
                            if (manager?.isRequestPinShortcutSupported == true) {
                                val shortcut = ShortcutInfo.Builder(context, "snapflow_home")
                                    .setShortLabel("SnapFlow")
                                    .setLongLabel("Abrir SnapFlow")
                                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                                    .build()
                                manager.requestPinShortcut(shortcut, null)
                                helper("Confirme a criação do atalho na tela inicial.")
                            } else helper("Adicione o SnapFlow à tela inicial pelo menu de aplicativos.")
                        }
                    )
                    1 -> BrowserScreen(
                        web = web, address = browserAddress,
                        onAddress = { browserAddress = it },
                        onNavigate = { goBrowser(browserAddress) },
                        onAnalyze = { source = web.url.orEmpty(); tab = 0; analyzeUrl(source) },
                        detectedCount = detected.size, onSources = { sheet = true }
                    )
                    else -> DownloadsScreen(jobs = jobs, engineUpdating = updatingEngine,
                        onEngineUpdate = { updateExtractor() },
                        onRetry = { job -> retryDownload(job) },
                        onOpen = { job ->
                        try {
                            val uri = Uri.parse(job.uri)
                            val mime = context.contentResolver.getType(uri) ?:
                                if (job.mode == "audio") "audio/*" else "video/*"
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            activity.startActivity(Intent.createChooser(view, "Abrir com"))
                        } catch (_: Exception) { helper("Não foi possível abrir o arquivo") }
                    }, onCancel = { job ->
                        JobStore.cancel(context, job)
                        jobs = JobStore.all(context)
                        helper("Download cancelado")
                    }, onClear = { job ->
                        JobStore.remove(context, job.id)
                        jobs = JobStore.all(context)
                    }, onSystemDownloads = {
                        try { activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                        catch (_: Exception) { helper("Gerenciador de downloads indisponível") }
                    })
                }
            }
        }
        if (sheet) {
            ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = CardColor) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                    Text("Arquivos encontrados", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("O site deve oferecer o arquivo para download.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    if (detected.isEmpty()) Text("Nenhuma mídia direta detectada. Tente analisar o endereço da página.", color = Muted)
                    LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(detected, key = { it.url }) { item ->
                            Card(colors = CardDefaults.cardColors(containerColor = CardRaised), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Muted)
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        sheet = false
                                        if (MediaEngine.isDirect(item.url)) addDirect(item.url, item.label)
                                        else { source = item.url; tab = 0; analyzeUrl(source) }
                                    }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                                        Text("Baixar / analisar", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    source: String, onSource: (String) -> Unit,
    analysis: MediaEngine.Analysis?, analyzing: Boolean, error: String,
    audio: Boolean, onAudio: (Boolean) -> Unit,
    quality: String, onQuality: (String) -> Unit,
    onAnalyze: () -> Unit, onDownload: () -> Unit,
    onPaste: () -> Unit, onBrowser: () -> Unit, onFiles: () -> Unit, onPin: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardRaised),
                shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(22.dp)) {
                    Surface(color = Accent.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
                        Text("  SIMPLES • RÁPIDO • ORGANIZADO  ",
                            modifier = Modifier.padding(vertical = 8.dp), fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, color = Accent)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Seu vídeo, no seu celular.", color = Light,
                        fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(7.dp))
                    Text("Cole o endereço, escolha o formato e acompanhe o download.",
                        color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = source, onValueChange = onSource,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        placeholder = { Text("Cole o link do vídeo...", color = Muted, fontSize = 14.sp) },
                        leadingIcon = { Text("🔗", fontSize = 18.sp) },
                        trailingIcon = { TextButton(onClick = onPaste) { Text("COLAR", fontWeight = FontWeight.Bold, fontSize = 12.sp) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onAnalyze, enabled = !analyzing,
                        modifier = Modifier.fillMaxWidth().height(53.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Ink, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Analisando mídia...", fontWeight = FontWeight.Bold)
                        } else Text("Analisar link  →", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }
        }
        if (error.isNotBlank()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF402B32)), shape = RoundedCornerShape(16.dp)) {
                Text(error, modifier = Modifier.padding(15.dp), color = Color(0xFFFFD4D8), fontSize = 13.sp)
            }
        }
        if (analysis != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp)) {
                    if (analysis.thumbnail.isNotBlank()) {
                        AsyncImage(
                            model = analysis.thumbnail, contentDescription = "Miniatura do vídeo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("MÍDIA IDENTIFICADA", color = Good, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(analysis.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 3)
                    Text(analysis.subtitle, color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(15.dp))
                    Text("Formato", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !audio, onClick = { onAudio(false) }, label = { Text("▶ Vídeo") })
                        FilterChip(selected = audio, onClick = { onAudio(true) }, label = { Text("♫ Áudio MP3") })
                    }
                    if (!audio && !analysis.direct) {
                        Spacer(Modifier.height(8.dp))
                        Text("Qualidade disponível", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = quality == "auto", onClick = { onQuality("auto") }, label = { Text("Melhor") }) }
                            items(analysis.heights) { h ->
                                FilterChip(selected = quality == h.toString(), onClick = { onQuality(h.toString()) }, label = { Text("${h}p") })
                            }
                        }
                    }
                    if (analysis.direct && audio) Text("Conversão de arquivo direto via motor de mídia.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("↓  Baixar agora", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                }
            }
        }
        item {
            Text("ACESSOS RÁPIDOS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickCard("◎", "Navegador", "Abra sites", Modifier.weight(1f), onBrowser)
                QuickCard("⇩", "Meus arquivos", "Veja o progresso", Modifier.weight(1f), onFiles)
            }
        }
        item {
            TextButton(onClick = onPin) { Text("⌂  Adicionar atalho à tela inicial", color = Accent) }
            Text("Baixe apenas conteúdo próprio ou autorizado. A compatibilidade depende do site; mídias com DRM não são suportadas.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun QuickCard(icon: String, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor)) {
        Column(Modifier.padding(16.dp)) {
            Text(icon, color = Accent, fontSize = 27.sp)
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BrowserScreen(
    web: WebView, address: String, onAddress: (String) -> Unit,
    onNavigate: () -> Unit, onAnalyze: () -> Unit,
    detectedCount: Int, onSources: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = address, onValueChange = onAddress, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("Pesquisar ou digitar endereço...") },
            trailingIcon = { TextButton(onClick = onNavigate) { Text("IR", fontWeight = FontWeight.Bold) } }
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (web.canGoBack()) web.goBack() }) { Text("‹ Voltar") }
            TextButton(onClick = { if (web.canGoForward()) web.goForward() }) { Text("Avançar ›") }
            TextButton(onClick = { web.reload() }) { Text("↻") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAnalyze) { Text("Analisar página", fontWeight = FontWeight.Bold) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color.White)) {
            AndroidView(
                factory = {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onSources,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (detectedCount > 0) Accent else CardRaised,
                contentColor = if (detectedCount > 0) Ink else Light)
        ) {
            Text(if (detectedCount > 0) "Ver $detectedCount arquivo(s) detectado(s)  ↓" else "Nenhum arquivo direto detectado",
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DownloadsScreen(
    jobs: List<DownloadJob>,
    engineUpdating: Boolean,
    onEngineUpdate: () -> Unit,
    onRetry: (DownloadJob) -> Unit,
    onOpen: (DownloadJob) -> Unit,
    onCancel: (DownloadJob) -> Unit,
    onClear: (DownloadJob) -> Unit,
    onSystemDownloads: () -> Unit
) {
    val active = jobs.count { it.status in listOf("Baixando", "Preparando", "Na fila", "Pausado pelo Android") }
    val failed = jobs.count { it.status == "Falhou" }
    val completed = jobs.count { it.status == "Concluído" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Downloads", color = Light, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text("$active em andamento  •  $completed concluídos  •  $failed falharam", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onEngineUpdate,
                    enabled = !engineUpdating && active == 0,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
                ) {
                    if (engineUpdating) {
                        CircularProgressIndicator(Modifier.size(17.dp), color = Ink, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (engineUpdating) "Atualizando..." else "↻ Atualizar motor", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onSystemDownloads, shape = RoundedCornerShape(13.dp)) {
                    Text("Abrir pasta", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "O download depende da permissão do site. A atualização do motor pode melhorar a compatibilidade, mas não libera conteúdo bloqueado.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        if (jobs.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 55.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⇩", fontSize = 46.sp, color = Accent)
                    Text("Nenhum download ainda", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Cole um link na tela Início para começar.", fontSize = 12.sp, color = Muted)
                }
            }
        }
        items(jobs, key = { it.id }) { job ->
            val running = job.status in listOf("Baixando", "Preparando", "Na fila", "Pausado pelo Android")
            val failure = job.status == "Falhou"
            Card(
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = CardColor)
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(CardRaised),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (job.mode == "audio") "♫" else "▶", color = Accent, fontSize = 22.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(job.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                            Spacer(Modifier.height(3.dp))
                            val format = if (job.mode == "audio") "MP3" else if (job.quality == "auto") "Vídeo" else job.quality + "p"
                            Text("$format  •  ${job.status}", color = if (failure) Color(0xFFFFA3A5) else if (job.status == "Concluído") Good else Muted,
                                fontSize = 12.sp)
                        }
                        if (running || job.status == "Concluído") {
                            Text("${job.progress}%", color = if (running) Accent else Good, fontSize = 12.sp)
                        }
                    }
                    if (running) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (job.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            color = Accent, trackColor = CardRaised
                        )
                    }
                    if (failure) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = Color(0xFF3B2932), shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("Falha ao baixar", color = Color(0xFFFFB6BA),
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(job.error.ifBlank { "A origem não concluiu a transferência." },
                                    fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFFFD3D5))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (job.status == "Concluído" && job.uri.isNotBlank()) {
                            Button(onClick = { onOpen(job) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp)) {
                                Text("Abrir", fontSize = 12.sp)
                            }
                        }
                        if (failure) {
                            Button(onClick = { onRetry(job) },
                                enabled = !engineUpdating,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)) {
                                Text("↻ Tentar de novo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (running) {
                            TextButton(onClick = { onCancel(job) }) { Text("Cancelar", fontSize = 12.sp) }
                        }
                        if (job.status in listOf("Cancelado", "Falhou", "Concluído")) {
                            TextButton(onClick = { onClear(job) }) { Text("Remover", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
