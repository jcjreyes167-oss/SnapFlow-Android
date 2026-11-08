# SnapFlow Android 2.1

Aplicativo Android (API 26+) em Kotlin. Interface escura, WebView, detecção de links diretos HTML5, escolha entre URLs que a origem disponibiliza, downloads simultâneos pelo DownloadManager e acesso aos arquivos salvos.

## Compilação
GitHub Actions: Actions > Compilar APK SnapFlow; o push na main também dispara a compilação.
O resultado da compilação estará no artefato SnapFlow-v2.1-APK, se o job concluir com sucesso. APK debug assinado para instalação manual.

## Limites
A detecção não funciona para blobs, HLS/DASH, DRM ou streams protegidos. Seletor de qualidade apenas entre arquivos diretos existentes. **Conversão para MP3 ainda não implementada.** Pausa/retomada são controladas pelo sistema Android, sem controles manuais. Somente baixe mídia para a qual tenha autorização.
