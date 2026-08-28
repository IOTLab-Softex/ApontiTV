class TvPowerScheduleRunner
  def self.run(now = Time.zone.now)
    current_time = now.strftime("%H:%M")
    current_weekday = now.wday.to_s
    Rails.logger.info("[TvPowerScheduleRunner] Agora: #{current_time} | Dia da semana: #{current_weekday}")

    Broadcast.find_each do |broadcast|
      begin
        next unless broadcast.tv_power_schedule_configured?

        service = TvDeviceService.new(broadcast)
        tv_power_state = service.power_state
        tv_online = tv_power_state == :on
        power_on_due = broadcast.tv_power_on_due?(now)
        power_off_due = broadcast.tv_power_off_due?(now)
        blocked_today = broadcast.tv_schedule_blocked_today?

        Rails.logger.info(
          "[TvPowerScheduleRunner] #{broadcast.name}: " \
          "power_state=#{tv_power_state} online=#{tv_online} reachable=#{service.reachable?} " \
          "power_on_due=#{power_on_due} power_off_due=#{power_off_due} " \
          "blocked_today=#{blocked_today} on_time=#{broadcast.tv_power_on_time.inspect} " \
          "off_time=#{broadcast.tv_power_off_time.inspect}"
        )

        if blocked_today
          if tv_power_state == :off
            broadcast.update_column(:last_tv_power_off_at, now) if broadcast.tv_power_off_time.present?
            broadcast.register_tv_power_action!(action: "power_off", status: "skipped", message: "Dia bloqueado e a TV ja estava desligada via ADB.", at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: dia bloqueado e a TV ja estava desligada via ADB.")
          elsif tv_online
            result = service.power_off
            broadcast.update_column(:last_tv_power_off_at, now)
            broadcast.register_tv_power_action!(action: "power_off", status: result.success? ? "success" : "failed", message: "Dia bloqueado: #{result.message}", at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: desligamento por dia bloqueado - #{result.message}")
          else
            broadcast.update_column(:last_tv_power_off_at, now) if broadcast.tv_power_off_time.present?
            broadcast.register_tv_power_action!(action: "power_off", status: "skipped", message: "Dia bloqueado e a TV ja estava desligada.", at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: hoje esta marcado como dia desligado, mantendo a TV offline.")
          end
          next
        end

        if power_off_due
          if tv_power_state == :off
            broadcast.update_column(:last_tv_power_off_at, now)
            broadcast.register_tv_power_action!(action: "power_off", status: "skipped", message: "Horario de desligar atingido, mas a TV ja estava desligada via ADB.", at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: horario de desligar atingido, mas a TV ja estava desligada via ADB.")
          elsif tv_online
            result = service.power_off
            broadcast.update_column(:last_tv_power_off_at, now)
            broadcast.register_tv_power_action!(action: "power_off", status: result.success? ? "success" : "failed", message: result.message, at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: desligamento agendado - #{result.message}")
          else
            broadcast.update_column(:last_tv_power_off_at, now)
            broadcast.register_tv_power_action!(action: "power_off", status: "skipped", message: "Horario de desligar atingido, mas a TV ja estava desligada.", at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: horario de desligar atingido, mas a TV ja estava desligada.")
          end
        elsif power_on_due
          if tv_power_state == :on
            broadcast.update_column(:last_tv_power_on_at, now)
            broadcast.register_tv_power_action!(action: "power_on", status: "skipped", message: "Horario de ligar atingido, mas a TV ja estava ligada via ADB.", at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: horario de ligar atingido, mas a TV ja estava ligada via ADB.")
          else
            result = service.power_on
            broadcast.update_column(:last_tv_power_on_at, now)
            broadcast.register_tv_power_action!(action: "power_on", status: result.success? ? "success" : "failed", message: result.message, at: now)
            Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: ligamento agendado - #{result.message}")
          end
        else
          Rails.logger.info("[TvPowerScheduleRunner] #{broadcast.name}: nenhum comando necessario neste minuto.")
        end
      rescue StandardError => e
        broadcast.register_tv_power_action!(action: "scheduler", status: "error", message: e.message, at: now)
        Rails.logger.error("[TvPowerScheduleRunner] #{broadcast.name}: erro ao processar agendamento - #{e.message}")
      end
    end
  end
end
