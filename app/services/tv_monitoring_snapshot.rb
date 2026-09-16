class TvMonitoringSnapshot
  STATUS_GUIDE = {
    overall: [
      [1, "Online", "TV ligada, aplicativo aberto e conteúdo Web ou vídeo ativo."],
      [2, "TV ligada com app fechado", "A TV responde na rede, mas o aplicativo não envia sinal."],
      [3, "TV desligada", "A TV não responde ao teste de rede."],
      [4, "Erro no app", "O aplicativo está aberto e comunicou erro."],
      [5, "Ligada sem conteúdo", "TV e aplicativo ativos, sem Web ou reprodução identificada."]
    ],
    tv: [[0, "Desligada"], [1, "Ligada"]],
    app: [[0, "Fechado"], [1, "Aberto"]],
    content: [[0, "Sem exibição"], [1, "Somente web"], [2, "Reproduzindo"], [3, "App aberto"], [4, "Erro"]]
  }.freeze

  def self.call(broadcasts = Broadcast.order(:id), now: Time.current, power_status_resolver: nil)
    power_status_resolver ||= ->(tv) { tv.tv_power_status }
    tvs = broadcasts.to_a
    power_statuses = tvs.map do |tv|
      Thread.new { power_status_resolver.call(tv) }
    end.map { |thread| thread.value rescue :off }

    {
      schema_version: 2,
      collected_at: now.to_i,
      tvs: tvs.each_with_index.map do |tv, index|
        presence = tv.effective_app_player_presence_status
        last_seen = tv.app_player_presence_updated_at
        web_only = tv.official_app_browser_rotation_enabled? && tv.official_app_web_only?
        operating_status =
          case presence
          when "offline" then 0
          when "error" then 4
          when "playing" then 2
          else web_only ? 1 : 3
          end
        power_status = power_statuses[index]
        tv_on = power_status == :on ? 1 : 0
        app_online = %w[online playing error].include?(presence) ? 1 : 0
        overall_status =
          if tv_on.zero?
            3
          elsif app_online.zero?
            2
          elsif operating_status == 4
            4
          elsif [1, 2].include?(operating_status)
            1
          else
            5
          end
        {
          id: tv.id, name: tv.name, ip: tv.tv_ip.to_s,
          tv_on: tv_on,
          app_online: app_online,
          app_status: { "offline" => 0, "online" => 1, "playing" => 2, "error" => 3 }.fetch(presence, 0),
          playing: presence == "playing" ? 1 : 0,
          web_only: web_only ? 1 : 0,
          operating_status: operating_status,
          expected_running: tv.status == "running" ? 1 : 0,
          overall_status: overall_status,
          last_seen: last_seen&.to_i || 0,
          last_seen_age: last_seen ? [(now - last_seen).to_i, 0].max : -1
        }
      end
    }
  end
end
