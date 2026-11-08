# SnapFlow Android 3.0

Aplicativo Android em Kotlin/Jetpack Compose para arquivos de mídia autorizados.

## Recursos
- Colar link e analisar formatos e qualidades disponíveis no site.
- Extração de vídeo e áudio usando yt-dlp e FFmpeg (áudio MP3 quando suportado).
- Navegador integrado e detecção de arquivos diretos HTML5.
- Até dois downloads simultâneos, progresso e cancelamento.
- Salva arquivos em Downloads/SnapFlow através do MediaStore.
- Compartilhamento de links de outros aplicativos.

## Gerar APK
O arquivo .github/workflows/android-apk.yml executa automaticamente no push para main ou manualmente em GitHub Actions. O APK debug assinado é publicado como artefato SnapFlow-v3.0-APK.

Android 10+ (API 29+) com suporte arm64-v8a e armeabi-v7a.

## Limitações
Sem contorno de DRM, login pago ou conteúdo restrito. Sites podem deixar de funcionar quando mudam seus mecanismos. Alguns serviços exigem autenticação não integrada. A geração do APK não substitui testes em aparelho físico. Primeira inicialização de yt-dlp e FFmpeg pode demorar.

## Licença
GPL-3.0, consulte LICENSE e THIRD_PARTY_NOTICES.md.
