package com.snapflow.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String,
    val mode: String,
    val quality: String,
    val kind: String = "extract",
    val status: String = "Na fila",
    val progress: Int = 0,
    val systemId: Long = -1,
    val uri: String = "",
    val error: String = "",
    val created: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("title", title)
        .put("mode", mode).put("quality", quality).put("kind", kind)
        .put("status", status).put("progress", progress).put("systemId", systemId)
        .put("uri", uri).put("error", error).put("created", created)

    companion object {
        fun fromJson(x: JSONObject): DownloadJob = DownloadJob(
            id = x.optString("id"),
            url = x.optString("url"),
            title = x.optString("title", "Mídia"),
            mode = x.optString("mode", "video"),
            quality = x.optString("quality", "auto"),
            kind = x.optString("kind", "extract"),
            status = x.optString("status", "Na fila"),
            progress = x.optInt("progress", 0),
            systemId = x.optLong("systemId", -1),
            uri = x.optString("uri"),
            error = x.optString("error"),
            created = x.optLong("created")
        )
    }
}

object JobStore {
    private const val PREF = "snapflow_jobs_v3"
    private const val KEY = "records"

    @Synchronized
    fun all(context: Context): List<DownloadJob> = try {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { DownloadJob.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.created }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    private fun save(context: Context, items: List<DownloadJob>) {
        val arr = JSONArray()
        items.take(250).forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun create(
        context: Context,
        url: String,
        title: String,
        quality: String,
        audio: Boolean,
        kind: String = "extract",
        systemId: Long = -1
    ): DownloadJob {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title.ifBlank { "Mídia" },
            mode = if (audio) "audio" else "video",
            quality = quality,
            kind = kind,
            systemId = systemId
        )
        save(context, listOf(job) + all(context))
        return job
    }

    @Synchronized
    fun get(context: Context, id: String): DownloadJob? =
        all(context).firstOrNull { it.id == id }

    @Synchronized
    fun change(
        context: Context,
        id: String,
        status: String? = null,
        progress: Int? = null,
        uri: String? = null,
        error: String? = null
    ) {
        val jobs = all(context)
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return
        val original = jobs[index]
        val updated = original.copy(
            status = status ?: original.status,
            progress = progress?.coerceIn(0, 100) ?: original.progress,
            uri = uri ?: original.uri,
            error = error ?: original.error
        )
        if (updated == original) return
        save(context, jobs.toMutableList().apply { set(index, updated) })
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    fun addDirect(context: Context, url: String, title: String = "Arquivo"): DownloadJob {
        require(url.startsWith("https://", true)) { "Use um endereço HTTPS" }
        val name = URLUtil.guessFileName(url, null, null)
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(110)
            .ifBlank { "download.bin" }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name).setDescription("Download autorizado pelo SnapFlow")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "SnapFlow/" + name)
            .setAllowedOverMetered(true)
        try {
            CookieManager.getInstance().getCookie(url)?.let {
                if (it.isNotBlank()) request.addRequestHeader("Cookie", it)
            }
        } catch (_: Exception) { }
        request.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android) SnapFlow/3.0")
        val id = dm.enqueue(request)
        return create(context, url, title, "Arquivo original", audio = false, kind = "direct", systemId = id)
    }

    fun refreshDirect(context: Context) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        all(context).filter { it.kind == "direct" && it.systemId >= 0 }.forEach { job ->
            if (job.status == "Cancelado") return@forEach
            try {
                dm.query(DownloadManager.Query().setFilterById(job.systemId))?.use { cur ->
                    if (!cur.moveToFirst()) return@use
                    fun number(column: String) = cur.getLong(cur.getColumnIndexOrThrow(column))
                    val downloaded = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val pct = if (total > 0) (downloaded * 100L / total).toInt().coerceIn(0, 100) else job.progress
                    val status = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                        DownloadManager.STATUS_SUCCESSFUL -> "Concluído"
                        DownloadManager.STATUS_FAILED -> "Falhou"
                        DownloadManager.STATUS_RUNNING -> "Baixando"
                        DownloadManager.STATUS_PAUSED -> "Pausado pelo Android"
                        else -> "Na fila"
                    }
                    val uri = if (status == "Concluído") dm.getUriForDownloadedFile(job.systemId)?.toString() ?: "" else ""
                    change(context, job.id, status, pct, uri)
                }
            } catch (_: Exception) { }
        }
    }

    fun cancel(context: Context, job: DownloadJob) {
        if (job.kind == "direct" && job.systemId >= 0) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(job.systemId)
            change(context, job.id, status = "Cancelado")
        } else {
            DownloadService.cancel(context, job.id)
        }
    }
}
