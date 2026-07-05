# Captura de tela com MSS

Este módulo adiciona uma captura de tela em tempo real para fluxos de acessibilidade em desktop.

## Instalação

```bash
pip install -r requirements.txt
```

## Uso básico

```python
from accessibility.captura_tela_mss import captura_tela

for captura in captura_tela(intervalo_ms=100, formato="bgra"):
    frame = captura.frame
    print(frame.shape, captura.duracao_ms)
```

## Capturando uma região específica

```python
from accessibility.captura_tela_mss import captura_tela

regiao = {
    "left": 100,
    "top": 100,
    "width": 800,
    "height": 600,
}

for captura in captura_tela(intervalo_ms=100, regiao=regiao, formato="bgra"):
    processar_frame(captura.frame)
```

## Parando por outra thread

```python
from threading import Event
from accessibility.captura_tela_mss import captura_tela

parar = Event()

for captura in captura_tela(intervalo_ms=100, parar_event=parar):
    if deve_parar():
        parar.set()
```

## Observações de performance

- `intervalo_ms=100` equivale a aproximadamente 10 FPS.
- `formato="bgra"` é o caminho mais rápido, pois evita conversão de canais.
- Use `regiao` quando possível para reduzir a área capturada e melhorar desempenho.
- Use `copiar_frame=True` apenas se precisar armazenar o frame depois do ciclo atual.
