# Análise visual da mesa de sinuca

Fluxo nativo Android que captura a tela (ou uma tela espelhada), analisa a mesa
de sinuca e desenha a jogada por cima com uma overlay flutuante — mira, caminho
provável da bola branca, impacto, caminho da bola alvo e rebatida na tabela.

## Componentes

- `MainActivity`: pede a permissão de janela flutuante (`SYSTEM_ALERT_WINDOW`) e
  o consentimento explícito de captura via `MediaProjectionManager.createScreenCaptureIntent()`.
- `ScreenCaptureService`: foreground service (`mediaProjection`) que usa
  `MediaProjection` + `VirtualDisplay` + `ImageReader` para capturar o frame atual.
- `ScreenFrameProcessor`: recebe cada `Bitmap`, roda o pipeline de análise e
  publica os indicadores da jogada.
- `DetectorConfig`: todos os parâmetros calibráveis do detector.
- `IndicatorOverlayService` / `IndicatorOverlayView`: janela `TYPE_APPLICATION_OVERLAY`
  (sem capturar toque) que desenha a jogada.
- `OverlayIndicatorBus`: canal entre a thread de captura e a overlay.

## Intervalo de captura

Aproximadamente 10 FPS:

```kotlin
private const val FRAME_INTERVAL_MS = 100L // ScreenCaptureService
```

## Pipeline de análise (`ScreenFrameProcessor`)

1. **Matriz leve** — reduz o `Bitmap` para uma matriz (RGB + HSV) com lado máximo
   `DetectorConfig.maxAnalysisSide`.
2. **Segmentação da mesa** — classifica os pixels do pano por HSV (perfil
   `GREEN`/`BLUE`/`GRAY` ou `AUTO`) e toma o maior componente conectado como a
   área jogável; calcula o retângulo da mesa e a margem das tabelas (`railInset`).
3. **Bolas** — componentes "não‑pano" estritamente dentro da mesa, filtrados por
   raio (fração da largura da mesa), circularidade e razão de aspecto. Caçapas,
   tabelas e fundo são descartados por tocarem a borda da mesa.
4. **Bola branca / principal** — escolhida por `valor × (1 − saturação)`.
5. **Taco** — blob longo e fino perto da bola principal; a direção da tacada vem
   do eixo principal (PCA) orientado da ponta de trás para a bola.
6. **Trajetória** — geometria 2D:
   - reta da branca até a **1ª colisão**: interseção raio‑círculo (bola) ou
     raio‑retângulo (tabela), o que vier primeiro;
   - **ghost ball** e ponto de **Impacto**;
   - **caminho da bola alvo** (linha tracejada com seta) até a tabela;
   - **rebatida** por reflexão do vetor na tabela (`maxRailReflections`);
   - **deflexão** aproximada da branca (perpendicular à linha de impacto).
7. **Suavização temporal** — tracker de bolas com EMA de posição/raio e EMA do
   ângulo de mira; bolas só aparecem após `ballPersistenceFrames`.
8. **Confiança** — a trajetória cheia só é desenhada quando a confiança da mira
   ≥ `minAimConfidence`. Sem taco, há um fallback por alinhamento entre bolas com
   confiança baixa (por padrão não desenhado, para não poluir a tela).

## Calibração (câmera / iluminação)

O detector é puramente visual, então a qualidade depende do enquadramento e da
luz. Os três pontos que mais precisam de ajuste em `DetectorConfig`:

| Sintoma | Ajuste |
| --- | --- |
| Mesa não é detectada / some | Trocar `clothProfile` (pano azul → `BLUE`, cinza → `GRAY`) ou alargar as faixas HSV (`greenHueRange`, `greenMinSat`, …). Reduzir `minTableAreaFraction` se a mesa for pequena no frame. |
| Bolas não aparecem ou aparecem demais | Ajustar `minBallRadiusFraction`/`maxBallRadiusFraction` conforme o zoom; afrouxar/apertar `ballMinCircularity` e `ballMaxAspect`. |
| Taco não é reconhecido / mira instável | Ajustar `cueMinElongation`, `cueSearchRadiusBallFactor`, `cueMaxOffsetBallFactor`; aumentar `aimSmoothing` para mais estabilidade. |
| Overlay piscando | Reduzir `positionSmoothing`/`aimSmoothing`, aumentar `ballPersistenceFrames`. |
| Nada é desenhado embora haja bolas | Baixar `minAimConfidence` (mostra o fallback) ou ligar `debugOverlay = true` para ver mesa/bolas/eixo do taco crus. |

Boas condições: câmera relativamente perpendicular à mesa, pano de cor uniforme,
sem reflexos fortes, bola branca visível e taco contrastando com o pano.

## Limitações conhecidas

- Assume a mesa aproximadamente **alinhada aos eixos** da imagem (retângulo). Em
  ângulos muito oblíquos as tabelas ficam imprecisas (falta correção de
  perspectiva/homografia).
- Pano **cinza** é mais difícil (baixa saturação, conflita com bolas claras).
- Só uma rebatida na tabela é projetada por padrão; física de efeito (spin) não
  é modelada.
