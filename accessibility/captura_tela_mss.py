"""Captura de tela em tempo real com MSS.

Este módulo foi desenhado para fluxos de acessibilidade em desktop, como
identificar elementos circulares em sistemas ERP e ajudar usuários com
restrições motoras. A função principal mantém uma instância do MSS aberta,
evita recriar objetos a cada frame e controla o ritmo para capturar a tela a
cada 100ms por padrão.
"""

from __future__ import annotations

from dataclasses import dataclass
from threading import Event
from time import perf_counter, sleep, time
from typing import Callable, Iterator, Literal, Mapping, Optional, Union

import mss
import numpy as np

FormatoFrame = Literal["bgra", "bgr", "rgb", "gray"]
RegiaoTela = Mapping[str, int]
CallbackCaptura = Callable[[np.ndarray, "CapturaFrame"], None]


@dataclass(frozen=True)
class CapturaFrame:
    """Metadados de um frame capturado."""

    frame: np.ndarray
    timestamp: float
    largura: int
    altura: int
    duracao_ms: float
    atraso_ms: float


def _montar_area_captura(
    sct: mss.mss,
    monitor: int,
    regiao: Optional[RegiaoTela],
    regiao_absoluta: bool,
) -> dict[str, int]:
    """Monta a área no formato esperado pelo MSS."""

    if monitor < 0 or monitor >= len(sct.monitors):
        raise ValueError(
            f"Monitor {monitor} inválido. Use um valor entre 0 e {len(sct.monitors) - 1}."
        )

    base = sct.monitors[monitor]
    if regiao is None:
        return {
            "left": int(base["left"]),
            "top": int(base["top"]),
            "width": int(base["width"]),
            "height": int(base["height"]),
        }

    obrigatorios = ("left", "top", "width", "height")
    faltando = [campo for campo in obrigatorios if campo not in regiao]
    if faltando:
        raise ValueError(f"Região sem campos obrigatórios: {', '.join(faltando)}")

    left = int(regiao["left"])
    top = int(regiao["top"])
    width = int(regiao["width"])
    height = int(regiao["height"])

    if width <= 0 or height <= 0:
        raise ValueError("A região precisa ter width e height maiores que zero.")

    if not regiao_absoluta:
        left += int(base["left"])
        top += int(base["top"])

    return {"left": left, "top": top, "width": width, "height": height}


def _converter_frame(screenshot: mss.screenshot.ScreenShot, formato: FormatoFrame, copiar: bool) -> np.ndarray:
    """Converte o frame para o formato desejado.

    BGRA é o formato mais rápido porque evita troca de canais. Use RGB apenas
    quando a próxima etapa realmente exigir esse layout.
    """

    bgra = np.frombuffer(screenshot.bgra, dtype=np.uint8).reshape(
        screenshot.height,
        screenshot.width,
        4,
    )

    if formato == "bgra":
        frame = bgra
    elif formato == "bgr":
        frame = bgra[:, :, :3]
    elif formato == "rgb":
        frame = bgra[:, :, 2::-1]
    elif formato == "gray":
        # Conversão inteira aproximada: 0.299 R + 0.587 G + 0.114 B.
        b = bgra[:, :, 0].astype(np.uint16)
        g = bgra[:, :, 1].astype(np.uint16)
        r = bgra[:, :, 2].astype(np.uint16)
        frame = ((r * 77 + g * 150 + b * 29) >> 8).astype(np.uint8)
    else:
        raise ValueError(f"Formato inválido: {formato}")

    return frame.copy() if copiar else frame


def iterar_captura_tela(
    *,
    intervalo_ms: int = 100,
    monitor: int = 1,
    regiao: Optional[RegiaoTela] = None,
    regiao_absoluta: bool = False,
    formato: FormatoFrame = "bgra",
    copiar_frame: bool = False,
    parar_event: Optional[Event] = None,
    max_frames: Optional[int] = None,
) -> Iterator[CapturaFrame]:
    """Gera frames da tela no intervalo configurado.

    Args:
        intervalo_ms: intervalo alvo entre capturas. O padrão de 100ms equivale
            a aproximadamente 10 FPS.
        monitor: índice do monitor usado pelo MSS. Use 1 para o monitor
            principal ou 0 para todos os monitores.
        regiao: recorte opcional com left, top, width e height.
        regiao_absoluta: quando False, a região é relativa ao monitor escolhido.
        formato: bgra, bgr, rgb ou gray. BGRA é o caminho mais rápido.
        copiar_frame: quando True, devolve uma cópia segura para armazenar depois.
        parar_event: Event opcional para interromper a captura por outra thread.
        max_frames: limite opcional, útil em testes.

    Yields:
        CapturaFrame contendo o array NumPy e metadados de tempo.
    """

    if intervalo_ms <= 0:
        raise ValueError("intervalo_ms precisa ser maior que zero.")

    intervalo_s = intervalo_ms / 1000.0
    frames_emitidos = 0

    with mss.mss() as sct:
        area = _montar_area_captura(sct, monitor, regiao, regiao_absoluta)
        proximo_tick = perf_counter()

        while max_frames is None or frames_emitidos < max_frames:
            if parar_event is not None and parar_event.is_set():
                break

            inicio = perf_counter()
            screenshot = sct.grab(area)
            frame = _converter_frame(screenshot, formato, copiar_frame)
            fim = perf_counter()

            atraso_ms = max(0.0, (inicio - proximo_tick) * 1000.0)
            yield CapturaFrame(
                frame=frame,
                timestamp=time(),
                largura=screenshot.width,
                altura=screenshot.height,
                duracao_ms=(fim - inicio) * 1000.0,
                atraso_ms=atraso_ms,
            )

            frames_emitidos += 1
            proximo_tick += intervalo_s
            espera = proximo_tick - perf_counter()

            if espera > 0:
                sleep(espera)
            else:
                # Se o processamento passou do alvo, recomeça o relógio para
                # evitar fila acumulada e manter baixa latência.
                proximo_tick = perf_counter()


def captura_tela(
    callback: Optional[CallbackCaptura] = None,
    *,
    intervalo_ms: int = 100,
    monitor: int = 1,
    regiao: Optional[RegiaoTela] = None,
    regiao_absoluta: bool = False,
    formato: FormatoFrame = "bgra",
    copiar_frame: bool = False,
    parar_event: Optional[Event] = None,
    max_frames: Optional[int] = None,
) -> Union[Iterator[CapturaFrame], None]:
    """Captura a tela em tempo real usando MSS.

    Quando `callback` não é informado, retorna um iterador:

        for captura in captura_tela(intervalo_ms=100):
            processar(captura.frame)

    Quando `callback` é informado, executa o loop e chama a função a cada frame:

        captura_tela(lambda frame, meta: print(meta.duracao_ms))
    """

    frames = iterar_captura_tela(
        intervalo_ms=intervalo_ms,
        monitor=monitor,
        regiao=regiao,
        regiao_absoluta=regiao_absoluta,
        formato=formato,
        copiar_frame=copiar_frame,
        parar_event=parar_event,
        max_frames=max_frames,
    )

    if callback is None:
        return frames

    for captura in frames:
        callback(captura.frame, captura)

    return None
