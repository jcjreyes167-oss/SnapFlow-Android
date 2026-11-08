package com.snapflow.app

import android.net.Uri

/**
 * Extrai somente URLs HTTPS válidas do texto copiado/compartilhado.
 * A identificação não concede acesso a mídias protegidas ou privadas.
 */
object SocialLinks {
    enum class Platform(val label: String) {
        YOUTUBE("YouTube"), TIKTOK("TikTok"), FACEBOOK("Facebook"),
        INSTAGRAM("Instagram"), OTHER("Site ou arquivo direto")
    }

    data class Parsed(val url: String, val platform: Platform, val looksLikeVideo: Boolean)

    private val finder = Regex("""https?://[^\s<>"'\\]+""", RegexOption.IGNORE_CASE)

    fun find(text: String): Parsed? {
        for (candidate in finder.findAll(text)) {
            val candidateUrl = candidate.value
                .replace("&amp;", "&")
                .trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '»', '”')
            val uri = try { Uri.parse(candidateUrl) } catch (_: Exception) { continue }
            val hostname = uri.host?.lowercase()?.removeSuffix(".").orEmpty()
            if (hostname.isEmpty() || uri.scheme !in listOf("https", "http")) continue
            val platform = when {
                domain(hostname, "youtube.com") || domain(hostname, "youtu.be") || domain(hostname, "youtube-nocookie.com") ->
                    Platform.YOUTUBE
                domain(hostname, "tiktok.com") || domain(hostname, "tiktokv.com") ->
                    Platform.TIKTOK
                domain(hostname, "facebook.com") || domain(hostname, "fb.watch") || domain(hostname, "fb.com") ->
                    Platform.FACEBOOK
                domain(hostname, "instagram.com") || domain(hostname, "instagr.am") ->
                    Platform.INSTAGRAM
                else -> Platform.OTHER
            }
            // Todos os acessos gerados pela aplicação precisam ser HTTPS.
            val normalized = uri.buildUpon().scheme("https").build().toString()
            val path = uri.path.orEmpty().lowercase()
            val video = when (platform) {
                Platform.YOUTUBE -> hostname.endsWith("youtu.be") ||
                    path.startsWith("/watch") || path.startsWith("/shorts/") ||
                    path.startsWith("/live/") || path.startsWith("/embed/")
                Platform.TIKTOK -> domain(hostname, "tiktok.com") &&
                    (path.contains("/video/") || path.startsWith("/t/") ||
                     hostname.startsWith("vm.") || hostname.startsWith("vt.")) ||
                    domain(hostname, "tiktokv.com")
                Platform.FACEBOOK -> domain(hostname, "fb.watch") ||
                    path.contains("/videos/") || path.startsWith("/watch") ||
                    path.startsWith("/reel/") || path.contains("/share/v/") ||
                    path.contains("/share/r/") || path.contains("/video.php")
                Platform.INSTAGRAM -> path.startsWith("/reel/") ||
                    path.startsWith("/reels/") || path.startsWith("/p/") ||
                    path.startsWith("/tv/")
                Platform.OTHER -> true
            }
            return Parsed(normalized, platform, video)
        }
        return null
    }

    private fun domain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
