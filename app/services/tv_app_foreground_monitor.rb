class TvAppForegroundMonitor
  REOPEN_COOLDOWN = 2.minutes
  RUN_LOCK = Mutex.new

  def self.run
    new.run
  end

  def run
    locked = RUN_LOCK.try_lock
    return unless locked

    Broadcast.where(status: "running")
             .where(tv_device_type: %w[android_tv fire_tv])
             .find_each do |broadcast|
      check_broadcast(broadcast)
    rescue StandardError => error
      Rails.logger.warn("[TvAppForegroundMonitor] #{broadcast.name}: #{error.class} - #{error.message}")
    end
  ensure
    RUN_LOCK.unlock if locked
  end

  private

  def check_broadcast(broadcast)
    return if broadcast.adb_target_host.blank?
    return if broadcast.effective_app_player_presence_status != "offline"
    return if reopen_cooldown_active?(broadcast)

    power_status = broadcast.mobile_tv_power_status
    return unless power_status == :on

    result = TvDeviceService.new(broadcast).open_official_app
    broadcast.update_columns(
      last_app_foreground_reopen_at: Time.current,
      last_app_foreground_reopen_message: result.message.to_s.truncate(240)
    )

    SystemNotification.emit!(
      event_key: result.success? ? "app_foreground_reopen_sent" : "app_foreground_reopen_failed",
      title: result.success? ? "App reaberto automaticamente" : "Falha ao reabrir app",
      message: "#{broadcast.name}: #{result.message}",
      severity: result.success? ? "success" : "error",
      source: broadcast,
      dedupe_for: REOPEN_COOLDOWN
    )

    Rails.logger.info("[TvAppForegroundMonitor] #{broadcast.name}: #{result.message}")
  end

  def reopen_cooldown_active?(broadcast)
    broadcast.last_app_foreground_reopen_at.present? &&
      broadcast.last_app_foreground_reopen_at > REOPEN_COOLDOWN.ago
  end
end
