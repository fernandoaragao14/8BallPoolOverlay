# ERP Accessibility Capture

Este fluxo roda nativamente no Android para apoiar testes de acessibilidade no ERP mobile.

## Componentes

- `MainActivity`: solicita permissão de janela flutuante e consentimento explícito de captura via `MediaProjectionManager.createScreenCaptureIntent()`.
- `ScreenCaptureService`: serviço foreground que usa `MediaProjection`, `VirtualDisplay` e `ImageReader` para capturar o frame atual da tela.
- `ScreenFrameProcessor`: recebe cada `Bitmap`, executa a detecção matricial e publica indicadores na overlay.
- `IndicatorOverlayService`: janela flutuante com `TYPE_APPLICATION_OVERLAY`, sem capturar toque, para desenhar indicadores visuais por cima do ERP.
- `OverlayIndicatorBus`: canal para atualizar círculos, caixas e linhas desenhados na tela.

## Intervalo de captura

O serviço captura a cada `100ms`, aproximadamente 10 FPS.

```kotlin
private const val FRAME_INTERVAL_MS = 100L
```

## Detecção matricial automática

`ScreenFrameProcessor` já executa um pipeline padrão quando recebe o frame:

1. reduz o `Bitmap` para uma matriz leve de luminância;
2. aplica bordas por Sobel;
3. agrupa componentes conectados;
4. pontua componentes circulares;
5. pontua caixas nos cantos da tela;
6. seleciona o círculo principal;
7. calcula vetores entre o círculo principal e os outros círculos;
8. prediz colisões em linha reta usando interseção raio-círculo;
9. envia `VisualIndicator` para o `OverlayIndicatorBus`.

A overlay suporta três formas:

```kotlin
VisualIndicatorShape.CIRCLE
VisualIndicatorShape.BOX
VisualIndicatorShape.LINE
```

## Ajuste manual ou processamento adicional

O detector automático fica ligado por padrão:

```kotlin
ScreenFrameProcessor.autoDetectEnabled = true
```

Use `ScreenFrameProcessor.listener` para receber os frames e complementar o processamento:

```kotlin
ScreenFrameProcessor.listener = { frame ->
    val bitmap = frame.bitmap

    // Processamento extra do ERP, se necessário.
}
```

Se quiser substituir totalmente o detector padrão:

```kotlin
ScreenFrameProcessor.autoDetectEnabled = false
ScreenFrameProcessor.listener = { frame ->
    val indicadores = listOf(
        VisualIndicator(
            x = 320f,
            y = 640f,
            radius = 44f,
            label = "Ação",
            shape = VisualIndicatorShape.CIRCLE
        )
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
