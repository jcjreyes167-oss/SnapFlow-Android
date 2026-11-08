package com.snapflow.app

import org.junit.Assert.*
import org.junit.Test

class SocialLinksTest {
    @Test fun recognizedPlatforms() {
        assertEquals(SocialLinks.Platform.YOUTUBE, SocialLinks.find("https://youtu.be/dQw4w9WgXcQ")?.platform)
        assertEquals(SocialLinks.Platform.YOUTUBE, SocialLinks.find("https://www.youtube.com/shorts/abc123")?.platform)
        assertEquals(SocialLinks.Platform.TIKTOK, SocialLinks.find("https://vt.tiktok.com/ZSabcdef/")?.platform)
        assertEquals(SocialLinks.Platform.FACEBOOK, SocialLinks.find("https://www.facebook.com/reel/123456")?.platform)
        assertEquals(SocialLinks.Platform.INSTAGRAM, SocialLinks.find("https://www.instagram.com/reel/abc123/")?.platform)
    }
    @Test fun clipboardAndShareText() {
        val p = SocialLinks.find("Veja o vídeo: https://vm.tiktok.com/ZM123456/ enviado por um amigo")
        assertEquals("https://vm.tiktok.com/ZM123456/", p?.url)
        assertTrue(p?.looksLikeVideo == true)
    }
    @Test fun distinguishProfileFromVideo() {
        assertFalse(SocialLinks.find("https://www.instagram.com/conta_exemplo/")!!.looksLikeVideo)
        assertFalse(SocialLinks.find("https://www.facebook.com/pagina-exemplo")!!.looksLikeVideo)
        assertTrue(SocialLinks.find("https://www.instagram.com/p/ABC123/")!!.looksLikeVideo)
    }
    @Test fun validHttpsOnly() {
        assertNull(SocialLinks.find("texto sem link"))
        assertEquals("https://www.facebook.com/watch?v=123",
            SocialLinks.find("http://www.facebook.com/watch?v=123")?.url)
    }
}
