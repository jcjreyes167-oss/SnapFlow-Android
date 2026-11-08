package com.snapflow.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object MediaEngine {
    data class Analysis(val title: String, val subtitle: String, val thumbnail: String, val heights: List<Int>, val direct: Boolean)
    private var initialized = false

    fun isDirect(url: String): Boolean {
        val path = try { Uri.parse(url).path.orEmpty().lowercase() } catch (_: Exception) { "" }
        return listOf(".mp4", ".mp3", ".m4a", ".webm", ".wav", ".ogg", ".mov", ".mkv").any { path.endsWith(it) }
    }

    @Synchronized
    fun ensure(context: Context) {
        if (initialized) return
        YoutubeDL.init(context.applicationContext)
        FFmpeg.init(context.applicationContext)
        initialized = true
    }

    fun analyze(context: Context, url: String): Analysis {
        require(url.startsWith("https://", true)) { "Cole um endereço HTTPS válido" }
        if (isDirect(url)) {
            val name = Uri.parse(url).lastPathSegment ?: "Arquivo de mídia"
            return Analysis(name, "Arquivo direto • Sem extração", "", emptyList(), true)
        }
        ensure(context)
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
        val info: VideoInfo = YoutubeDL.getInfo(request)
        val heights = info.formats.orEmpty()
            .filter { it.height > 0 && it.vcodec != "none" }
            .map { it.height }
            .distinct().sortedDescending().take(12)
        val subtitle = listOfNotNull(info.uploader?.takeIf { it.isNotBlank() }, info.extractor)
            .joinToString(" • ")
        return Analysis(
            title = info.title ?: "Vídeo encontrado",
            subtitle = subtitle.ifBlank { "Fonte identificada" },
            thumbnail = info.thumbnail.orEmpty(),
            heights = heights,
            direct = false
        )
    }

    fun execute(context: Context, job: DownloadJob, report: (Int, Long) -> Unit): Uri {
        ensure(context)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "temp/" + job.id)
        dir.mkdirs()
        val request = YoutubeDLRequest(job.url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--no-warnings")
            .addOption("-o", File(dir, "%(title).100B.%(ext)s").absolutePath)
        if (job.mode == "audio") {
            request.addOption("-f", "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            val format = if (job.quality == "auto") {
                "bv*+ba/b"
            } else {
                val h = job.quality.toIntOrNull() ?: 720
                "bv*[height<=$h]+ba/b[height<=$h]/best"
            }
            request.addOption("-f", format)
            request.addOption("--merge-output-format", "mp4")
        }
        YoutubeDL.execute(request, job.id) { progress, eta, _ ->
            report(progress.toInt().coerceIn(0, 99), eta)
        }
        val outputs = dir.listFiles()?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }
            ?.filter { file -> listOf(".mp4", ".webm", ".mkv", ".mp3", ".m4a", ".opus", ".ogg", ".wav", ".mov").any { file.name.lowercase().endsWith(it) } }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val result = outputs.firstOrNull() ?: throw IllegalStateException("Nenhum arquivo finalizado. Verifique a compatibilidade do site.")
        val destination = publish(context, result)
        dir.deleteRecursively()
        return destination
    }

    private fun publish(context: Context, file: File): Uri {
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SnapFlow")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Não foi possível salvar o arquivo na pasta Downloads")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            } ?: throw IllegalStateException("Não foi possível gravar o arquivo")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}

class DownloadService : Service() {
    private val pool = Executors.newFixedThreadPool(2)
    private val active = AtomicInteger(0)
    private val channel = "snapflow_downloads"

    companion object {
        private const val START = "snapflow.start"
        private const val CANCEL = "snapflow.cancel"
        private const val JOB = "job_id"
        fun enqueue(context: Context, id: String) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .setAction(START).putExtra(JOB, id))
        }
        fun cancel(context: Context, id: String) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .setAction(CANCEL).putExtra(JOB, id))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "Downloads SnapFlow", NotificationManager.IMPORTANCE_LOW))
        startForeground(3451, notification("Preparando downloads"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel)
            .setContentTitle("SnapFlow")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun notifyStatus(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(3451, notification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(JOB)
        when (intent?.action) {
            CANCEL -> {
                if (id != null) {
                    JobStore.change(this, id, status = "Cancelado")
                    YoutubeDL.destroyProcessById(id)
                }
                if (active.get() == 0) stopSelf()
            }
            START -> {
                if (id != null && JobStore.get(this, id) != null) {
                    active.incrementAndGet()
                    pool.execute { runDownload(id) }
                } else if (active.get() == 0) stopSelf()
            }
            else -> if (active.get() == 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runDownload(id: String) {
        try {
            val job = JobStore.get(this, id) ?: return
            if (job.status == "Cancelado") return
            JobStore.change(this, id, status = "Preparando", progress = 0)
            notifyStatus("Preparando " + job.title.take(45))
            var last = -1
            val uri = MediaEngine.execute(this, job) { progress, eta ->
                if (last != progress) {
                    last = progress
                    if (JobStore.get(this, id)?.status != "Cancelado") {
                        JobStore.change(this, id, status = "Baixando", progress = progress)
                        if (progress % 10 == 0) notifyStatus(job.title.take(24) + " • " + progress + "%")
                    }
                }
            }
            if (JobStore.get(this, id)?.status != "Cancelado") {
                JobStore.change(this, id, status = "Concluído", progress = 100, uri = uri.toString())
                notifyStatus("Arquivo salvo em Downloads/SnapFlow")
            }
        } catch (e: Exception) {
            if (JobStore.get(this, id)?.status != "Cancelado") {
                val error = e.message.orEmpty().take(240).ifBlank { "Falha de extração ou conexão" }
                JobStore.change(this, id, status = "Falhou", error = error)
                notifyStatus("Falha no download. Verifique o link.")
            }
        } finally {
            if (active.decrementAndGet() <= 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdown()
    }
}
