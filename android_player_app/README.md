# apontiTV

App Android nativo para:

- buscar a lista de TVs no Rails em `GET /broadcasts/mobile_index.json`
- mostrar a lista na tela inicial
- abrir o stream da TV escolhida em tela cheia

## URL do Rails

A URL base usada pelo app esta em:

- `android_player_app/app/build.gradle.kts`

Campo:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.119:3000\"")
```

Troque pelo IP da maquina onde o Rails esta rodando na rede local.

## Estrutura

- `MainActivity`: carrega a lista de TVs
- `PlayerActivity`: reproduz o HLS em tela cheia com Media3/ExoPlayer
- `TvApiClient`: consome o endpoint JSON do Rails

## Endpoint do Rails

O backend agora expõe:

- `/broadcasts/mobile_index.json`

Resposta exemplo:

```json
[
  {
    "id": 2,
    "name": "TV SOFTEX ADM",
    "stream_url": "http://192.168.1.119:8080/hls/stream2.m3u8",
    "status": "running",
    "tv_power_status": "off",
    "orientation": "portrait"
  }
]
```
