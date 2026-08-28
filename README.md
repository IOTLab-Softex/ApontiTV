# Aponti TV

Plataforma para gerenciar TVs, playlists, conteudos, wallpapers de computadores e exibicao em rede local ou acesso externo.

## O que o sistema faz

- Dashboard web para administracao das TVs.
- Cadastro e configuracao de TVs Android, Fire Stick e telas comuns.
- Publicacao de playlists com videos e imagens.
- Biblioteca de midias com thumbnails leves.
- Player Android oficial para exibicao direta nas TVs.
- Reproducao via app oficial ou fluxo FFmpeg/HLS.
- Alternancia entre playlist e pagina web no app oficial.
- Sincronizacao de TVs usando a mesma playlist.
- Status em tempo real de app aberto, producao e energia da TV.
- Comandos por ADB para abrir app, play, stop, energia, volume e launcher quando suportado.
- Agendamento de ligar/desligar por TV e configuracao global.
- Widgets no player com hora, data, clima, UV e imagens animadas de clima.
- Central de notificacoes de eventos importantes das TVs.
- Logs de acesso e atividade dos usuarios.
- Agente Windows para aplicar wallpaper em computadores aprovados.
- Tela de releases com historico visual do produto.
- Aponti Studio, editor de video com timeline multicamada.

## Componentes

- `Rails 7`: painel web, APIs e regras de negocio.
- `Devise`: autenticacao de usuarios.
- `SQLite`: banco local do projeto.
- `Active Storage`: armazenamento de midias, thumbnails, wallpapers e assets enviados.
- `FFmpeg`: conversao, preparacao de videos e fluxos HLS.
- `Nginx`: entrega de streaming/HLS quando usado.
- `android_player_app`: app Android oficial das TVs.
- `bin/aponti_wallpaper_agent.ps1`: agente Windows de wallpaper.

## Rotas principais

- `/dashboard`: dashboard principal.
- `/broadcasts`: TVs, cards, play, stop, volume, publicar playlist e adicionar TV.
- `/broadcasts/new`: criar TV.
- `/broadcasts/:id/edit`: editar TV.
- `/media_library`: biblioteca de midias.
- `/video_editor/new`: Aponti Studio.
- `/streaming_configurations/:id/edit`: configuracoes do sistema.
- `/desktop_agents`: computadores/agentes de wallpaper.
- `/notifications`: historico de notificacoes.
- `/releases`: releases do Aponti TV.

## Requisitos

- Windows.
- Ruby `3.3.x`.
- Bundler.
- FFmpeg instalado e acessivel pelo PATH.
- Nginx em `C:\nginx`, quando usar HLS.
- Android Debug Bridge `adb`, quando controlar TVs Android/Fire Stick.

## Configuracao local

```powershell
$rubyRoot='C:\Ruby33-x64'
$env:PATH="$rubyRoot\bin;$rubyRoot\msys64\ucrt64\bin;$rubyRoot\msys64\usr\bin;" + $env:PATH
$env:RAILS_ENV='development'
$env:RACK_ENV='development'

bundle install
bundle exec rails db:migrate
bundle exec rails server -b 0.0.0.0 -p 3000
```

Acesse:

```text
http://localhost:3000
```

## Rodando em producao

Gere uma chave segura:

```powershell
ruby -e "require 'securerandom'; puts SecureRandom.hex(64)"
```

Configure `SECRET_KEY_BASE` no ambiente ou em `config/windows_startup.env`, usando `config/windows_startup.env.example` como modelo.

Depois rode:

```powershell
$rubyRoot='C:\Ruby33-x64'
$env:PATH="$rubyRoot\bin;$rubyRoot\msys64\ucrt64\bin;$rubyRoot\msys64\usr\bin;" + $env:PATH
$env:RAILS_ENV='production'
$env:RACK_ENV='production'
$env:SECRET_KEY_BASE='troque_pela_sua_chave'

bundle install
bundle exec rails db:migrate
bundle exec rails assets:precompile
bundle exec rails server -e production -b 0.0.0.0 -p 3000
```

## Inicializacao com Windows

Scripts disponiveis:

- `bin/windows_start_aponti_tv.bat`
- `bin/windows_stop_aponti_tv.bat`
- `bin/install_windows_startup.bat`

O arquivo `config/windows_startup.env` nao deve ser versionado porque contem segredos locais.

## App Android

O app oficial fica em:

```text
android_player_app
```

Funcoes principais:

- Selecionar TV/tela.
- Reproduzir playlists com imagens e videos.
- Exibir paginas web.
- Receber comandos do servidor.
- Reportar presenca, status e item em reproducao.
- Aplicar orientacao da TV e do layout.
- Mostrar widgets de clima/hora quando habilitado.
- Reabrir automaticamente quando configurado.
- Sincronizar playlist pelo horario do servidor.

## Agente Windows

O agente permite aplicar wallpaper nos computadores cadastrados e aprovados.

Fluxo:

1. O computador solicita acesso.
2. O administrador aprova no painel.
3. O agente recebe token automaticamente.
4. O servidor publica wallpaper.
5. O agente online baixa e aplica a imagem.

## Documentacao adicional

- `docs/APONTI_TV_DOCUMENTACAO.md`: documentacao funcional completa.
- `docs/APONTI_TV_RELEASES.html`: pagina visual de releases.
- `docs/vpn_adb_remoto.md`: notas sobre ADB remoto/VPN.

## Observacoes de seguranca

- Nunca versionar `config/master.key`.
- Nunca versionar `config/windows_startup.env`.
- Evitar subir builds, caches, bundles, node_modules, prints de teste e arquivos temporarios.
- ADB deve ser usado somente em rede confiavel.
