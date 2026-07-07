package app.hack.eightballpool

/**
 * Parâmetros calibráveis do detector de sinuca.
 *
 * Tudo aqui é `var` para permitir ajuste em runtime (ex.: futuro painel de
 * calibração) sem recompilar. Os valores padrão foram escolhidos para uma mesa
 * filmada de cima/diagonal com pano verde, iluminação razoável.
 *
 * As três fontes de erro que mais exigem calibração de câmera/iluminação:
 * 1. cor do pano ([clothProfile] / tolerâncias HSV) — pano azul/cinza precisa trocar o perfil;
 * 2. tamanho aparente das bolas ([minBallRadiusFraction]/[maxBallRadiusFraction]) — depende do zoom;
 * 3. reconhecimento do taco ([cueMinElongation]/[cueSearchRadiusBallFactor]) — depende do ângulo.
 */
object DetectorConfig {

    /** Perfil de cor do pano. AUTO escolhe entre GREEN/BLUE/GRAY pelo que cobre mais pixels. */
    enum class ClothProfile { AUTO, GREEN, BLUE, GRAY }

    // ---- Resolução de análise --------------------------------------------------
    /**
     * Maior lado da matriz de análise (px). Maior = mais preciso e mais lento.
     * 480 mantém boa latência (tempo real na mirada); o scan da linha checa
     * vizinhança 3x3, então a linha fina do jogo ainda é detectada.
     */
    var maxAnalysisSide: Int = 480

    // ---- Segmentação da mesa ---------------------------------------------------
    /** Padrão BLUE: o pano do jogo 8 Ball Pool é azul/ciano. Use AUTO/GREEN para mesa real. */
    var clothProfile: ClothProfile = ClothProfile.BLUE

    // Faixas HSV por perfil. Hue em [0,360), sat/val em [0,1].
    var greenHueRange: ClosedFloatingPointRange<Float> = 80f..175f
    var greenMinSat: Float = 0.22f
    var greenValRange: ClosedFloatingPointRange<Float> = 0.12f..0.96f

    // Faixa alargada p/ ciano do jogo; valor mínimo maior exclui a UI azul-escura ao redor da mesa.
    var blueHueRange: ClosedFloatingPointRange<Float> = 165f..250f
    var blueMinSat: Float = 0.20f
    var blueValRange: ClosedFloatingPointRange<Float> = 0.32f..0.99f

    /** Cinza: baixa saturação e valor médio (evita capturar as bolas brancas, que têm val alto). */
    var grayMaxSat: Float = 0.20f
    var grayValRange: ClosedFloatingPointRange<Float> = 0.20f..0.72f

    /** A mesa precisa cobrir ao menos esta fração do frame para ser considerada válida. */
    var minTableAreaFraction: Float = 0.10f

    /** Encolhe a área jogável para dentro (fração da largura da mesa) para bater na tabela, não no pano externo. */
    var railInsetFraction: Float = 0.012f

    // ---- Detecção de bolas -----------------------------------------------------
    /** Raio das bolas como fração da largura da mesa. */
    var minBallRadiusFraction: Float = 0.011f
    var maxBallRadiusFraction: Float = 0.060f

    /** Circularidade mínima (área do blob / área da bounding box; círculo ideal ≈ 0.785). */
    var ballMinCircularity: Float = 0.50f

    /** Razão de aspecto máxima da bounding box (largura/altura ou inverso). */
    var ballMaxAspect: Float = 1.9f

    /** Máximo de bolas consideradas por frame. */
    var maxBalls: Int = 16

    // ---- Bola branca (principal) ----------------------------------------------
    var cueBallMinValue: Float = 0.70f
    var cueBallMaxSat: Float = 0.30f

    // ---- Linha de mira do jogo (8 Ball Pool) -----------------------------------
    /** Ler a linha branca de mira que o próprio jogo desenha a partir da bola branca. */
    var readGameAimLine: Boolean = true

    /** Pixel "branco" da linha: valor alto e saturação baixa. */
    var aimLineMinValue: Float = 0.74f
    var aimLineMaxSat: Float = 0.26f

    /**
     * Scan radial: dispara raios da bola branca em todas as direções e acha a
     * linha branca mais longa do jogo. Mais robusto e em tempo real.
     */
    var guidelineAngleStepDeg: Float = 1.5f

    /** Buracos (pixels não-brancos) tolerados ao longo da linha antes de cortar. */
    var guidelineMaxGap: Int = 3

    /** Comprimento mínimo da linha (em raios de bola) para aceitar a mira. */
    var guidelineMinRunBallFactor: Float = 2.2f

    // ---- Detecção do taco ------------------------------------------------------
    /** Raio de busca do taco ao redor da bola principal, em múltiplos do raio da bola. */
    var cueSearchRadiusBallFactor: Float = 10f

    /** Alongamento mínimo (sqrt(λ1/λ2) da PCA) para um blob ser considerado taco. */
    var cueMinElongation: Float = 3.2f

    /** Pixels mínimos (na matriz de análise) para o blob do taco. */
    var cueMinPixels: Int = 36

    /** Distância perpendicular máxima do eixo do taco até a bola principal, em raios de bola. */
    var cueMaxOffsetBallFactor: Float = 1.6f

    // ---- Modo automático de jogadas (oportunidades) ----------------------------
    /**
     * Varre a mesa e destaca sozinho as melhores jogadas (bola -> caçapa).
     * Desligado por padrão: é derivado da detecção (mais ruidoso). A mira ao vivo
     * estendida da linha do jogo é mais confiável. Ligue pela notificação se quiser.
     */
    var opportunityMode: Boolean = false

    /** Quantas jogadas destacar (a melhor fica em destaque, as demais mais fracas). */
    var maxOpportunities: Int = 3

    /** Ângulo de corte máximo aceito (graus). Acima disso a tacada é fina demais. */
    var maxCutAngleDeg: Float = 72f

    /** Raio da "boca" da caçapa como fração da largura da mesa (folga do caminho até o buraco). */
    var pocketRadiusFraction: Float = 0.035f

    /** Folga extra (em raios de bola) exigida para considerar um caminho "livre" de obstáculos. */
    var pathClearanceBallFactor: Float = 0.4f

    // ---- Trajetória ------------------------------------------------------------
    /** Quantas rebatidas na tabela desenhar depois do primeiro impacto na borda. */
    var maxRailReflections: Int = 1

    /** Desenhar a deflexão aproximada da branca após bater em outra bola. */
    var drawCueDeflection: Boolean = true

    // ---- Suavização temporal ---------------------------------------------------
    /** Peso da nova medição na EMA de posição (0..1). Maior = segue mais rápido a mira. */
    var positionSmoothing: Float = 0.6f

    /** Peso da nova medição na EMA do ângulo de mira. Alto para acompanhar a mirada ao vivo. */
    var aimSmoothing: Float = 0.65f

    /** Frames consecutivos que uma bola precisa aparecer para ser desenhada (1 = imediato). */
    var ballPersistenceFrames: Int = 1

    /** Frames que uma bola some antes de ser descartada do tracker. */
    var ballMaxMissFrames: Int = 6

    // ---- Confiança -------------------------------------------------------------
    /** Confiança mínima da mira para desenhar a trajetória cheia. */
    var minAimConfidence: Float = 0.30f

    // ---- Debug -----------------------------------------------------------------
    /**
     * Desenha caixas/pontos de diagnóstico (mesa bruta, candidatos, eixo da mira).
     * Ligado por padrão nesta versão de calibração — desligue depois de afinar.
     */
    var debugOverlay: Boolean = true
}
