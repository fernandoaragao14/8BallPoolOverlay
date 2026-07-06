# ERP Accessibility Capture

Este fluxo roda nativamente no Android para apoiar testes de acessibilidade no ERP mobile.

## Componentes

- `MainActivity`: solicita permissão de janela flutuante e consentimento explícito de captura via `MediaProjectionManager.createScreenCaptureIntent()`.
- `ScreenCaptureService`: serviço foreground que usa `MediaProjection`, `VirtualDisplay` e `ImageReader` para capturar o frame atual da tela.
- `ScreenFrameProcessor`: ponto interno para receber cada `Bitmap` capturado.
- `IndicatorOverlayService`: janela flutuante com `TYPE_APPLICATION_OVERLAY`, sem capturar toque, para desenhar indicadores visuais por cima do ERP.
- `OverlayIndicatorBus`: canal para atualizar os indicadores desenhados na tela.

## Intervalo de captura

O serviço captura a cada `100ms`, aproximadamente 10 FPS.

```kotlin
private const val FRAME_INTERVAL_MS = 100L
```

## Onde plugar a detecção

Use `ScreenFrameProcessor.listener` para receber os frames e detectar elementos redondos do ERP:

```kotlin
ScreenFrameProcessor.listener = { frame ->
    val bitmap = frame.bitmap

    // Detectar alvos no bitmap.
    val indicadores = listOf(
        VisualIndicator(x = 320f, y = 640f, radius = 44f, label = "Ação")
    )

    OverlayIndicatorBus.setIndicators(indicadores)
}
```

Se o processamento for assíncrono, copie o bitmap antes de guardar:

```kotlin
val safeCopy = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
```

## Segurança e privacidade

A captura depende do consentimento nativo do Android por `MediaProjection`. Os frames ficam no processamento interno do app e não há envio de imagem para rede nesta implementação.
