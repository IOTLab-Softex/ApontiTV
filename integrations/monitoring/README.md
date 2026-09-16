# Aponti TV → Zabbix → Grafana

Instalado em 09/09/2026: Aponti `192.168.1.98:3000`, Zabbix 6.4.17 e Grafana 11.1.3 em `192.168.1.109`.

Painel: http://192.168.1.109:3000/d/aponti-tv-status/aponti-tv-status

## Funcionamento

- `GET /monitoring/tvs` retorna somente cadastro e estado do player, mediante `Authorization: Bearer <token>`. Não retorna senhas, tokens de dispositivos ou URLs de conteúdo e não executa comandos nas TVs.
- O token vem de `APONTI_MONITORING_TOKEN` ou de `config/monitoring.token` (ignorado pelo Git). Preserve esse arquivo ao atualizar o servidor.
- O host `aponti-tv-inventory` consulta o cadastro a cada 10 segundos. Sua descoberta cria um host `aponti-tv-<id>` para cada TV com IP cadastrado, no grupo `Aponti TV / TVs`.
- Nome e IP são atualizados na descoberta; IDs mantêm a identidade das TVs. Cadastros removidos expiram após uma hora (mais o processamento da descoberta). A exclusão do host também remove seu histórico do Zabbix. Falhas HTTP não são tratadas como lista vazia.
- Cada host usa o template `Aponti TV por HTTP`, com coleta a cada 10 segundos e itens dependentes para os estados do aplicativo. Ping e latência são coletados independentemente pelo Zabbix.
- Não é necessário instalar agente Zabbix ou atualizar o APK para esse monitoramento.
- O painel usa consultas por grupo, sem lista fixa de TVs. Atualiza a cada 10 segundos; os cartões consultam os últimos dois minutos para evitar mostrar amostras antigas como atuais. O histórico usa seis horas.

## Interpretação

O painel mantém estados separados: **TV** usa o ping (`0` desligada, `1` ligada); **aplicativo** usa o heartbeat (`0` fechado, `1` aberto); **conteúdo** usa `0` sem exibição, `1` somente web, `2` reproduzindo, `3` app aberto e `4` erro. Alguns aparelhos em espera ainda podem responder ao ping. Nunca conectado usa idade `-1`.

O item calculado **Aponti: status geral** consolida os sinais: `1` online, `2` TV ligada com app fechado, `3` TV desligada, `4` erro no app e `5` ligada sem conteúdo identificado. Somente web e reproduzindo geram `1` quando TV e app estão ativos.

## Manutenção

`provision_zabbix.py` permite reaplicar a configuração pela API oficial. Requer um token administrativo em `ZABBIX_API_TOKEN` e o token do Aponti em `APONTI_MONITORING_TOKEN`. URLs opcionais: `ZABBIX_API_URL` e `APONTI_MONITORING_URL`. Segredos não devem ser colocados no código ou versionados.

`build_dashboard.py` gera `aponti-tv-status.json`. O datasource existente tem UID `bdlokpjbz277kd`; adapte-o se migrar o Grafana.

No Raspberry, o painel está em `/var/lib/grafana/dashboards/aponti/aponti-tv-status.json`, com provider `/etc/grafana/provisioning/dashboards/aponti-tv.yaml`. O dashboard original permanece separado. Edições pela interface são permitidas, mas uma nova publicação do JSON substitui as edições desse painel provisionado.

Os dados HTTP circulam na rede local; para uso fora dessa rede, configure HTTPS. O token do monitoramento é separado das credenciais dos usuários.

## Validação

Teste Rails: `bundle exec rails test test/controllers/tv_monitoring_controller_test.rb`. Cobre autenticação, campos exportados, expiração da presença, alteração do nome e remoção da lista.

Verificados no ambiente: descoberta das quatro TVs, coleta dos itens sem erro, ping, dashboard registrado e consultas de dados pelo Grafana. Nenhuma TV real foi removida para teste; a remoção é controlada pelo ciclo de descoberta do Zabbix.

Referências: [Descoberta do Zabbix](https://www.zabbix.com/documentation/6.4/en/manual/discovery/low_level_discovery), [Provisionamento do Grafana](https://grafana.com/docs/grafana/latest/administration/provisioning/).
