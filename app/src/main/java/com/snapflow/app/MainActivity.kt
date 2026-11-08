package com.snapflow.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

class MainActivity : Activity() {
    private val background = Color.rgb(15, 18, 25)
    private val card = Color.rgb(33, 40, 52)
    private val accent = Color.rgb(255, 152, 58)
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var panel: LinearLayout
    private lateinit var subtitle: TextView
    private val links = linkedMapOf<String, Media>()
    private val downloads by lazy { getSystemService(DOWNLOAD_SERVICE) as DownloadManager }
    private val events = Handler(Looper.getMainLooper())
    private var tab = 0
    data class Media(val url: String, val label: String, val mime: String?)
    private val tick = object : Runnable {
        override fun run() {
            if (tab == 1) showDownloads()
            events.postDelayed(this, 1600)
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "▶  SnapFlow"
            textSize = 26f
            setTextColor(accent)
            setPadding(18.dp, 16.dp, 12.dp, 10.dp)
        })
        val tabs = LinearLayout(this)
        listOf("Navegar", "Downloads", "Biblioteca").forEachIndexed { i, name ->
            tabs.addView(btn(name) { tab = i; render() }, LinearLayout.LayoutParams(0, 52.dp, 1f))
        }
        root.addView(tabs)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        subtitle = label("Mídias autorizadas • Somente links HTTPS", 12f)
        root.addView(subtitle)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (this@MainActivity::address.isInitialized && tab == 0) address.setText(url.orEmpty())
                    inspectPage()
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return request?.url?.scheme != "https"
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener { url, _, disposition, mime, _ ->
                if (url.startsWith("https://", true)) {
                    val item = Media(url, URLUtil.guessFileName(url, disposition, mime), mime)
                    links[url] = item
                    runOnUiThread { confirm(item) }
                }
            }
        }
        render()
        web.loadUrl("https://example.org")
        events.post(tick)
    }

    private fun btn(title: String, callback: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(card)
        setOnClickListener { callback() }
    }
    private fun label(value: String, size: Float = 15f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(8.dp, 10.dp, 8.dp, 10.dp)
    }
    private fun render() {
        panel.removeAllViews()
        when (tab) {
            0 -> showBrowser()
            1 -> showDownloads()
            else -> showLibrary()
        }
    }
    private fun showBrowser() {
        val top = LinearLayout(this)
        address = EditText(this).apply {
            setSingleLine(true)
            hint = "https://exemplo.com"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(web.url.orEmpty())
        }
        top.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("Ir") { visit() })
        panel.addView(top)
        val actions = LinearLayout(this)
        actions.addView(btn("‹ Voltar") { if (web.canGoBack()) web.goBack() })
        actions.addView(btn("Detectar") { inspectPage(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(btn("Link direto") { directLink() })
        panel.addView(actions)
        (web.parent as? ViewGroup)?.removeView(web)
        panel.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(btn("Arquivos e qualidades disponíveis") { listMedia() })
    }
    private fun visit() {
        val input = address.text.toString().trim()
        if (!input.startsWith("https://", true)) {
            notice("Use um endereço HTTPS válido")
            return
        }
        links.clear()
        web.loadUrl(input)
    }
    private fun inspectPage(show: Boolean = false) {
        val javascript = """(function(){
            var found=[];
            document.querySelectorAll('video[src],video source[src],audio[src],audio source[src],a[href]').forEach(function(el){
                var u=el.src||el.href||'';
                if(!u.startsWith('https://'))return;
                var media=el.tagName!=='A';
                if(!media && !/\.(mp4|webm|mp3|m4a|aac|ogg|wav)(\?|#|$)/i.test(u))return;
                var label=el.getAttribute('label')||el.getAttribute('data-res')||el.getAttribute('res')||el.textContent.trim().slice(0,45)||u.split('/').pop();
                found.push({url:u,label:label,mime:el.type||''});
            });
            return JSON.stringify(found.slice(0,100));
        })()"""
        web.evaluateJavascript(javascript) { raw ->
            try {
                val decoded = JSONArray("[" + raw + "]").getString(0)
                val detected = JSONArray(decoded)
                for (i in 0 until detected.length()) {
                    val obj = detected.getJSONObject(i)
                    val u = obj.optString("url")
                    if (u.startsWith("https://", true)) {
                        links[u] = Media(u, obj.optString("label", "Arquivo"), obj.optString("mime"))
                    }
                }
                subtitle.text = links.size.toString() + " arquivo(s) / fonte(s) detectado(s)"
                if (show) listMedia()
            } catch (_: Exception) {
                if (show) notice("Nenhuma mídia direta encontrada")
            }
        }
    }
    private fun listMedia() {
        if (links.isEmpty()) {
            notice("Nenhum link direto detectado. Use Link direto.")
            return
        }
        val options = links.values.toList()
        AlertDialog.Builder(this)
            .setTitle("Selecione o arquivo ou qualidade")
            .setItems(options.map { it.label + "  •  " + Uri.parse(it.url).lastPathSegment.orEmpty() }.toTypedArray()) { _, choice ->
                confirm(options[choice])
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
    private fun directLink() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "https://servidor/arquivo.mp4"
        }
        AlertDialog.Builder(this).setTitle("Download HTTPS direto").setView(input)
            .setPositiveButton("Avançar") { _, _ ->
                confirm(Media(input.text.toString().trim(), "Arquivo direto", null))
            }
            .setNegativeButton("Cancelar", null).show()
    }
    private fun confirm(item: Media) {
        if (!item.url.startsWith("https://", true)) {
            notice("Apenas links HTTPS")
            return
        }
        AlertDialog.Builder(this).setTitle("Confirmar download")
            .setMessage(item.label + "\n\nA qualidade depende dos arquivos disponibilizados pela origem. Apenas conteúdo autorizado.")
            .setPositiveButton("Baixar") { _, _ -> download(item) }
            .setNegativeButton("Cancelar", null).show()
    }
    private fun download(item: Media) {
        try {
            val name = URLUtil.guessFileName(item.url, null, item.mime)
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(name)
                .setDescription("SnapFlow • Mídia autorizada")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SnapFlow/" + name)
                .setAllowedOverMetered(true)
            if (!item.mime.isNullOrBlank()) request.setMimeType(item.mime)
            web.settings.userAgentString?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(item.url)?.let { request.addRequestHeader("Cookie", it) }
            val id = downloads.enqueue(request)
            getSharedPreferences("snapflow_jobs", MODE_PRIVATE).edit().putLong(id.toString(), id).apply()
            notice("Download adicionado à fila do Android")
        } catch (e: Exception) {
            notice("Erro de download: " + e.message.orEmpty())
        }
    }
    private fun showDownloads() {
        if (tab != 1) return
        panel.removeAllViews()
        panel.addView(label("Gerenciador de downloads", 20f))
        panel.addView(btn("Abrir downloads do Android") {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ids = getSharedPreferences("snapflow_jobs", MODE_PRIVATE).all.keys
            .mapNotNull { it.toLongOrNull() }.sortedDescending()
        if (ids.isEmpty()) list.addView(label("Nenhum download iniciado."))
        ids.forEach { id ->
            downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fun get(name: String) = cursor.getColumnIndexOrThrow(name)
                    val name = cursor.getString(get(DownloadManager.COLUMN_TITLE)) ?: "Arquivo"
                    val soFar = cursor.getLong(get(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(get(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val state = cursor.getInt(get(DownloadManager.COLUMN_STATUS))
                    val progress = if (total > 0) (soFar * 100 / total).toString() + "%"
                        else (soFar / 1024).toString() + " KB"
                    val stateText = when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> "Concluído"
                        DownloadManager.STATUS_FAILED -> "Falha"
                        DownloadManager.STATUS_PAUSED -> "Pausado pelo sistema"
                        DownloadManager.STATUS_RUNNING -> "Baixando"
                        else -> "Aguardando"
                    }
                    list.addView(label(name + "\n" + stateText + " • " + progress))
                    val buttons = LinearLayout(this)
                    if (state == DownloadManager.STATUS_SUCCESSFUL) buttons.addView(btn("Abrir") {
                        val uri = downloads.getUriForDownloadedFile(id)
                        if (uri != null) {
                            try {
                                val type = cursor.getString(get(DownloadManager.COLUMN_MEDIA_TYPE)) ?: "*/*"
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) { notice("Nenhum aplicativo pode abrir este arquivo") }
                        }
                    })
                    buttons.addView(btn("Remover") {
                        downloads.remove(id)
                        getSharedPreferences("snapflow_jobs", MODE_PRIVATE).edit().remove(id.toString()).apply()
                        showDownloads()
                    })
                    list.addView(buttons)
                }
            }
        }
        scroll.addView(list)
        panel.addView(scroll)
    }
    private fun showLibrary() {
        panel.addView(label("Biblioteca", 20f))
        panel.addView(label("Arquivos baixados são salvos em Downloads/SnapFlow."))
        panel.addView(btn("Abrir Downloads") {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        })
        panel.addView(label("Conversão para MP3", 18f))
        panel.addView(label("Ainda não disponível nesta compilação. É necessário integrar um codificador MP3 licenciado e compatível com Android.", 13f))
    }
    private fun notice(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    @Deprecated("Legacy back handling")
    override fun onBackPressed() {
        if (tab == 0 && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        events.removeCallbacks(tick)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
