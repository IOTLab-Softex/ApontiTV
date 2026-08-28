# Aponti TV - Documentacao do Sistema

## 1. Visao Geral

O Aponti TV e uma plataforma para gerenciar TVs, players Android, playlists de midia, exibicao de paginas web, wallpapers de computadores e operacao de conteudo em rede local ou acesso externo.

O sistema e composto por:

- Painel Web Rails: dashboard administrativo usado no navegador.
- App Android Aponti TV: player instalado nas TVs Android.
- Nginx/FFmpeg: camada de streaming HLS quando a reproducao usa FFmpeg.
- Agente de Wallpaper Windows: aplicativo/servico de fundo para aplicar wallpaper em computadores aprovados.
- Banco de dados Rails: armazena TVs, usuarios, playlists, midias, agentes, notificacoes e configuracoes.

## 2. Principais Objetivos

- Cadastrar TVs e configurar como cada uma deve reproduzir conteudo.
- Publicar videos, imagens e playlists para uma ou varias TVs.
- Reproduzir conteudo direto no app oficial Android ou via fluxo FFmpeg/HLS.
- Alternar playlist com pagina web no app oficial.
- Monitorar status das TVs, do app Android e da producao.
- Sincronizar TVs que usam a mesma playlist para exibirem a mesma tela ao mesmo tempo.
- Enviar wallpapers para computadores com agente aprovado.
- Gerenciar usuarios, permissoes, logs de acesso e notificacoes.

## 3. Perfis de Usuario

O sistema trabalha com usuarios autenticados via Devise.

Perfis principais:

- Administrador: gerencia TVs, usuarios, configuracoes, agentes, logs, playlists e funcoes sensiveis.
- Cliente: pode acessar funcoes liberadas, como publicar playlist, mas nao deve editar/excluir TVs nem acessar logs administrativos.
- Root: usuario padrao de maior permissao usado para acoes especiais, como comandos root/ADB e configuracoes avancadas.

## 4. Dashboard de TVs

Rota principal:

```text
/broadcasts
/dashboard
/
```

Funcoes:

- Lista TVs em cards e tabela.
- Mostra preview leve por thumbnail.
- Abre preview grande em modal.
- Atualiza preview em tempo real conforme a TV muda o item em reproducao.
- Mostra status resumidos por icones:
  - App aberto/fechado.
  - Em producao/parado.
  - TV ligada/desligada/sem IP.
- Permite publicar playlist.
- Permite adicionar nova TV.
- Permite comandos por TV:
  - Play.
  - Stop.
  - Ligar/desligar TV.
  - Volume/mute.
  - Editar, quando permitido.
  - Excluir, quando permitido.
  - Definir/remover Aponti TV como launcher principal em TV Android, quando aplicavel.

## 5. Cadastro e Configuracao de TV

Rotas:

```text
/broadcasts/new
/broadcasts/:id/edit
```

Configuracoes principais:

- Nome da TV.
- Tipo de TV:
  - TV Android.
  - TV normal.
- Tipo de reproducao:
  - App oficial.
  - App generico.
- Orientacao:
  - Retrato.
  - Paisagem.
- IP da TV.
- Porta ADB.
- Host ADB.
- MAC address para Wake-on-LAN.
- Horarios de ligar/desligar.
- Dias desabilitados.
- Playlist da biblioteca.
- Pagina web no app oficial.
- Alternancia entre video/playlist e pagina web.
- Quando abrir a pagina web:
  - Por intervalo de tempo.
  - Ao terminar video.
  - Conforme configuracao do broadcast.
- Efeito e duracao de transicao.

## 6. Tipos de Reproducao

### 6.1 App Oficial Android

O app Android recebe JSON do servidor e reproduz diretamente:

- Video MP4 preparado.
- Imagens.
- Playlists.
- Paginas web via WebView.
- Alternancia entre playlist/video e pagina web.

Vantagens:

- Menos dependencia de FFmpeg.
- Melhor controle de status.
- Presenca em tempo real.
- Suporte a playlist com imagens e videos.
- Suporte a transicoes.

### 6.2 FFmpeg / HLS

O Rails gera comando FFmpeg e o Nginx entrega HLS.

Uso:

- Fluxo `hls/stream.m3u8`.
- Preview via arquivo HLS.
- Exportacao M3U.

Dependencias:

- `C:\nginx`
- FFmpeg acessivel pelo sistema.
- Nginx rodando na porta configurada.

## 7. Playlists

Rotas:

```text
/broadcast_playlists.json
/broadcast_playlists/:id.json
/broadcast_playlists/upload_media.json
/broadcast_playlists/media_library.json
```

Funcoes:

- Criar playlist.
- Editar playlist.
- Excluir playlist.
- Selecionar midias da biblioteca.
- Subir novas midias pelo modal.
- Reordenar itens por arrastar e soltar.
- Definir tempo de cada imagem.
- Definir comportamento dos videos:
  - Obedecer tempo da imagem.
  - Esperar video terminar.
- Definir efeito de transicao:
  - Fade.
  - Slide.
  - Blur.
  - Zoom.
  - Wipe.
  - Push.
  - Flip.
  - Cross zoom.
  - Aponti Smooth.
  - Sem efeito.
- Definir duracao da transicao.
- Ativar sincronizacao entre TVs.

## 8. Sincronizacao de Playlist entre TVs

Opcao no modal de playlist:

```text
Sincronizar TVs
Mesma playlist, mesma tela
```

Como funciona:

- O servidor envia para o app:
  - Se a sincronizacao esta ativa.
  - Horario inicial da playlist.
  - Horario atual do servidor.
  - ID da playlist.
- O app Android calcula qual item deveria estar tocando naquele momento.
- Se uma TV entrar atrasada, ela pula direto para o item correto.
- TVs com a mesma playlist publicada exibem a mesma tela em tempos equivalentes.

Observacao:

- Quando a sincronizacao esta ativa, videos tambem seguem o tempo configurado da playlist para manter as TVs juntas.

## 9. Biblioteca de Midia

Rota:

```text
/media_library
```

Funcoes:

- Upload de videos.
- Upload de imagens.
- Listagem da biblioteca.
- Exclusao de midias.
- Geracao de thumbnails leves.
- Preview sem carregar o arquivo original inteiro.

Tipos aceitos:

- `video/*`
- `image/*`

## 10. Publicacao de Playlist

No dashboard, o botao de publicar abre o modal de publicacao.

Fluxo:

1. Seleciona uma playlist.
2. Seleciona uma ou varias TVs.
3. Publica nas TVs.
4. O sistema copia os itens para cada broadcast.
5. Se necessario, reinicia a reproducao.
6. O app Android recebe nova configuracao pelo polling.
7. A TV para/prepara/reproduz o novo conteudo.

## 11. Preview no Dashboard

O dashboard usa preview leve para nao travar.

Comportamento:

- O card carrega primeiro sem pesar a pagina.
- A thumbnail e carregada sob demanda.
- O modal abre a fonte original/normal.
- O preview muda quando a TV muda o item atual.
- O modal tambem acompanha a TV em tempo real.

Endpoints usados:

```text
/broadcasts/:id/mobile_thumbnail
/broadcasts/:id/mobile_prepared_video
/broadcasts/:id/mobile_playlist_item/:item_id
/media_library/:id/thumbnail
```

## 12. Status em Tempo Real

O dashboard consulta:

```text
/broadcasts/mobile_index.json
```

Status principais:

- App aberto:
  - Informado pela presenca do player Android.
- Em producao:
  - Indica que a TV esta reproduzindo ou foi iniciada.
- TV ligada/desligada:
  - Verificado via ADB, Wake-on-LAN ou status possivel.
- Item atual da playlist:
  - Informado pelo app Android com `playlist_item_id`.

Endpoints do app:

```text
POST /broadcasts/:id/mobile_presence
POST /broadcasts/:id/mobile_player_status
GET  /broadcasts/:id/mobile_status
GET  /broadcasts/mobile_index.json
```

## 13. App Android Aponti TV

Pasta:

```text
android_player_app
```

Componentes:

- `MainActivity`: tela inicial/lista de TVs e selecao.
- `PlayerActivity`: reproducao em tela cheia.
- `TvApiClient`: comunicacao com Rails.
- `TvChannel`: modelo dos canais/TVs.
- `DirectVideoCache`: cache de video direto, quando habilitado.
- `RemoteImageLoader`: carrega imagens remotas.
- `ServerSettingsActivity`: configuracao de servidor local/publico.
- `DeviceIdentityResolver`: identifica IPs da TV.
- `DeviceSession`: token unico do dispositivo.

Funcoes:

- Busca lista de TVs.
- Identifica a TV pelo token/IP.
- Autoabre a TV correspondente.
- Mostra tela de selecao.
- Reproduz video.
- Reproduz imagem.
- Reproduz playlist.
- Abre pagina web no WebView.
- Alterna playlist/video com pagina web.
- Envia presenca e status ao servidor.
- Envia erro de reproducao.
- Suporta transicoes.
- Suporta sincronizacao de playlist.
- Suporta configuracao de servidor local ou publico.

## 14. Configuracao Local e Publica no App Android

O app pode trabalhar em:

- Local: servidor na mesma rede.
- Publico: servidor acessado por IP publico ou dominio.

Configuracoes:

- Servidor local.
- Servidor publico.
- Modo de acesso.

Observacao:

- Para comandos ADB funcionarem fora da rede local, e necessario VPN, rede roteada ou outro metodo seguro de acesso ao IP/porta da TV.

## 15. Comandos ADB para TV Android

Funcoes usadas:

- Ligar/desligar ou enviar comandos quando suportado.
- Ajustar volume.
- Mute.
- Definir Aponti TV como launcher principal.
- Remover Aponti TV como launcher principal.
- Verificar se o app esta instalado.

Exemplo ja utilizado:

```powershell
adb -s 192.168.1.151:5555 shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb -s 192.168.1.151:5555 shell cmd package set-home-activity br.com.softextv.player/br.com.softextv.player.MainActivity
adb -s 192.168.1.151:5555 shell input keyevent HOME
```

## 16. Wallpapers de Desktop

O dashboard possui card Desktop para enviar wallpaper.

Funcoes:

- Upload de wallpaper.
- Preview do wallpaper.
- Botao aplicar.
- Progresso visual da aplicacao.
- Barra conforme agentes recebem/aplicam.
- Agentes online respondem ao servidor.
- Agente aplica wallpaper no Windows.

## 17. Agente Windows de Wallpaper

Arquivos principais:

```text
bin/aponti_wallpaper_agent.ps1
bin/install_aponti_wallpaper_agent.ps1
bin/build_aponti_wallpaper_installer.ps1
app/controllers/agent_pairings_controller.rb
app/controllers/agent_wallpapers_controller.rb
app/models/desktop_agent.rb
app/services/desktop_wallpaper_payload.rb
```

Fluxo de aprovacao:

1. Agente abre tela de instalacao/configuracao.
2. Usuario escolhe local ou publico.
3. Agente solicita pareamento ao servidor.
4. Administrador aprova o computador no painel.
5. Agente recebe token.
6. Agente roda em segundo plano.
7. Agente informa presenca ao servidor.
8. Quando ha wallpaper novo, baixa e aplica.
9. Informa aplicacao ao servidor.

## 18. Usuarios

Rotas:

```text
/users
/users/:id/edit
/users/edit
```

Funcoes:

- Criar usuario.
- Editar usuario.
- Alterar senha.
- Excluir usuario.
- Controle de perfil.
- Restricao de funcoes para cliente.
- Login com e-mail/senha.
- Opcao de login Google quando configurada.

## 19. Login

O sistema usa Devise.

Funcoes:

- Login com e-mail e senha.
- Lembrar-me.
- Layout customizado Aponti TV.
- Tema escuro fixo na tela de login, conforme configuracao atual.
- Login Google opcional quando configurado no sistema.
- Login Google deve aceitar apenas e-mails ja cadastrados.

## 20. Notificacoes

Rotas:

```text
/notifications
POST /notifications/mark_all_read
```

Funcoes:

- Notificar mudancas importantes.
- Badge aparece apenas quando ha notificacoes nao lidas.
- Marcar todas como lidas.
- Notificacoes relacionadas a:
  - TV ligada/desligada.
  - App abriu/fechou.
  - Reproducao iniciou/parou.
  - Erro retornado pelo app.
  - Playlist publicada.
  - Falha em comandos.

## 21. Logs de Acesso e Auditoria

Rota:

```text
/access_logs
```

Funcoes:

- Registrar acoes dos usuarios.
- Registrar ultimo acesso.
- Registrar saida/logout.
- Registrar operacoes relevantes no sistema.
- Ocultar logs para usuarios cliente.

## 22. Configuracoes de Streaming

Rota:

```text
/streaming_configurations/:id/edit
```

Funcoes:

- IP do servidor.
- Porta.
- Configuracoes de streaming.
- Som de notificacao que o app da TV toca quando recebe playlist nova.
- Controle para iniciar/parar streaming quando aplicavel.

## 23. Editor de Video

Rotas:

```text
/video_editor
/video_editor/new
/video_editor/:id/edit
```

Funcoes:

- Criar videos pelo sistema.
- Importar midias.
- Salvar projetos.
- Gerenciar duracoes, transicoes e elementos.
- Integrar midias criadas com a biblioteca.

## 24. Agendamentos

Rotas:

```text
/schedules
/schedules/bulk_create
/schedules/destroy_schedule
```

Funcoes:

- Criar periodos agendados para broadcasts.
- Associar video a intervalo.
- Excluir agendamento.

## 25. Exportacao M3U

Rota:

```text
/export_m3u_broadcasts
```

Funcao:

- Exporta lista M3U com as URLs de streaming das TVs.

## 26. Widgets

Rotas:

```text
/widgets
/widgets/calendario
/widgets/clima
/temperature
```

Funcoes:

- Exibir widgets auxiliares.
- Calendario.
- Clima.
- Temperatura.

## 27. Temas e Idiomas

Funcoes:

- Tema claro/escuro no painel.
- Sidebar compacta/expandida por hover.
- Logo adaptada ao tema.
- Idioma portugues/ingles.
- O idioma selecionado pelo usuario e lembrado para proximos acessos.

## 28. Inicializacao no Windows

Arquivos:

```text
bin/aponti_tv.bat
bin/windows_start_aponti_tv.bat
bin/windows_stop_aponti_tv.bat
bin/install_windows_startup.bat
config/windows_startup.env
config/windows_startup.env.example
```

Funcoes:

- Iniciar Rails em producao.
- Iniciar Nginx.
- Preparar ambiente Ruby.
- Registrar inicializacao com Windows.
- Parar servicos.

Comando comum:

```powershell
cmd /c bin\aponti_tv.bat auto
```

## 29. Producao

Comandos comuns:

```powershell
$ruby33='C:\Ruby33-x64\bin'
$env:PATH="$ruby33;" + ((($env:PATH -split ';') | Where-Object { $_ -notmatch 'Ruby32-x64' }) -join ';')
$env:RAILS_ENV='production'
$env:RACK_ENV='production'
$env:SECRET_KEY_BASE=(ruby -e "require 'securerandom'; puts SecureRandom.hex(64)")
bundle exec rails db:migrate
bundle exec rails assets:precompile
cmd /c bin\aponti_tv.bat auto
```

Verificar portas:

```powershell
netstat -ano | Select-String ':3000|:80'
Get-Process ruby,nginx -ErrorAction SilentlyContinue
```

## 30. Estrutura de Pastas Importantes

```text
app/controllers
app/models
app/views
app/assets
app/javascript
android_player_app
bin
config
db/migrate
public/wallpapers
```

## 31. Principais Endpoints JSON

TV/App:

```text
GET  /broadcasts/mobile_index.json
GET  /broadcasts/:id/mobile_status.json
POST /broadcasts/:id/mobile_presence.json
POST /broadcasts/:id/mobile_player_status.json
GET  /broadcasts/:id/mobile_thumbnail
GET  /broadcasts/:id/mobile_prepared_video
GET  /broadcasts/:id/mobile_playlist_item/:item_id
```

Playlists:

```text
GET    /broadcast_playlists.json
GET    /broadcast_playlists/:id.json
POST   /broadcast_playlists.json
PATCH  /broadcast_playlists/:id.json
DELETE /broadcast_playlists/:id.json
POST   /broadcast_playlists/upload_media.json
GET    /broadcast_playlists/media_library.json
```

Agente:

```text
POST /agent/pairings
GET  /agent/pairings/:id
GET  /agent/wallpaper
POST /agent/wallpaper/applied
```

Notificacoes:

```text
GET  /notifications
POST /notifications/mark_all_read
```

## 32. Dependencias Operacionais

- Ruby 3.3 x64.
- Rails 7.
- SQLite no ambiente atual.
- Nginx em `C:\nginx`.
- FFmpeg em `C:\nginx\ffmpeg\bin\ffmpeg.exe` ou no PATH.
- Android Debug Bridge para comandos em TV Android.
- JDK/JBR para build do app Android.
- Rede local ou acesso publico configurado.

## 33. Observacoes de Seguranca

- O app Android usa token de dispositivo para identificar a TV.
- Agentes Windows usam fluxo de pareamento e aprovacao.
- Usuarios cliente nao devem acessar logs nem funcoes administrativas.
- Comandos ADB devem ser restritos a rede confiavel ou VPN.
- Acesso publico deve ser protegido por firewall, DNS correto e politicas de rede.
- Login Google deve aceitar somente usuarios previamente cadastrados.

## 34. Fluxo Recomendado de Uso

1. Configurar servidor e Nginx.
2. Criar usuario administrador/root.
3. Cadastrar TVs.
4. Instalar app Android nas TVs.
5. Configurar modo local/publico no app.
6. Aprovar/identificar TVs.
7. Subir midias na biblioteca.
8. Criar playlist.
9. Opcionalmente ativar sincronizacao.
10. Publicar playlist nas TVs.
11. Acompanhar status no dashboard.
12. Usar notificacoes/logs para auditoria.
13. Instalar agentes Windows se for usar wallpaper em computadores.

