class Broadcast < ApplicationRecord
  APP_PLAYER_PRESENCE_TTL = 45.seconds
  OFFICIAL_APP_CURRENT_VERSION_NAME = ENV.fetch("APONTI_TV_APP_VERSION_NAME", "1.2.0")
  OFFICIAL_APP_CURRENT_VERSION_CODE = ENV.fetch("APONTI_TV_APP_VERSION_CODE", "3").to_i

  validates :official_app_login_username, presence: true, if: :official_app_login_enabled?
  validate do
    if official_app_login_enabled? && official_app_login_password.blank?
      errors.add(:official_app_login_password, "deve ser informada para o login automático")
    end
  end

  def official_app_login_password
    ciphertext = official_app_login_password_ciphertext
    web_login_encryptor.decrypt_and_verify(ciphertext) if ciphertext.present?
  end

  # An empty password on the edit form preserves the saved secret.
  def official_app_login_password=(value)
    return if value.blank?
    self.official_app_login_password_ciphertext = web_login_encryptor.encrypt_and_sign(value)
  end

  def web_login_encryptor
    key = Rails.application.key_generator.generate_key("broadcast-web-login-v1", 32)
    ActiveSupport::MessageEncryptor.new(key, cipher: "aes-256-gcm")
  end
  private :web_login_encryptor

  WEEKDAY_OPTIONS = [
    ["Domingo", "0"],
    ["Segunda", "1"],
    ["Terca", "2"],
    ["Quarta", "3"],
    ["Quinta", "4"],
    ["Sexta", "5"],
    ["Sabado", "6"]
  ].freeze

  has_one_attached :video
  has_many :playlist_items, -> { ordered }, class_name: "BroadcastPlaylistItem", dependent: :destroy
  has_many :schedules, dependent: :destroy

  attribute :playback_app_type, :string, default: "official_app"
  attribute :tv_device_type, :string, default: "normal_tv"
  attribute :orientation, :string, default: "portrait"
  attribute :adb_port, :integer, default: 5555
  attribute :tv_disabled_weekdays, default: []
  attribute :official_app_switch_interval_seconds, :integer, default: 300
  attribute :official_app_page_duration_seconds, :integer, default: 15
  attribute :official_app_transition_style, :string, default: "blur"
  attribute :official_app_transition_duration_ms, :integer, default: 900
  attribute :official_app_rotation_trigger, :string, default: "time_interval"
  attribute :official_app_switch_interval_unit, :string, default: "minutes"
  attribute :official_app_page_duration_unit, :string, default: "seconds"
  attribute :official_app_web_enabled, :boolean, default: false
  attribute :official_app_web_only, :boolean, default: false
  attribute :widget_bar_edge_spacing_enabled, :boolean, default: true
  attribute :widget_forecast_enabled, :boolean, default: true
  attribute :keep_app_foreground_enabled, :boolean, default: false

  serialize :tv_disabled_weekdays, coder: JSON

  enum :playback_app_type, {
    official_app: "official_app",
    generic_app: "generic_app"
  }, prefix: true

  enum :tv_device_type, {
    normal_tv: "normal_tv",
    android_tv: "android_tv",
    fire_tv: "fire_tv"
  }, prefix: true

  validates :name, presence: true
  validates :playback_app_type, presence: true
  validates :tv_device_type, presence: true
  validates :orientation, inclusion: { in: %w[portrait portrait_inverted landscape] }, allow_blank: true
  validates :tv_ip,
            format: {
              with: /\A((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)\z/,
              message: "deve ser um IPv4 valido"
            },
            allow_blank: true
  validates :adb_host,
            format: {
              with: /\A[a-zA-Z0-9.-]+\z/,
              message: "deve ser um IP ou hostname valido"
            },
            allow_blank: true
  validates :tv_mac_address,
            format: {
              with: /\A([0-9A-Fa-f]{2}[:\-]?){5}[0-9A-Fa-f]{2}\z/,
              message: "deve ser um MAC valido"
            },
            allow_blank: true
  validates :adb_port,
            numericality: { only_integer: true, greater_than: 0, less_than_or_equal_to: 65_535 },
            allow_nil: true
  validates :official_app_page_url,
            format: {
              with: URI::DEFAULT_PARSER.make_regexp(%w[http https]),
              message: "deve ser uma URL valida iniciando com http:// ou https://"
            },
            allow_blank: true
  validates :official_app_switch_interval_seconds,
            numericality: { only_integer: true, greater_than: 0, less_than_or_equal_to: 86_400 },
            allow_nil: true
  validates :official_app_page_duration_seconds,
            numericality: { only_integer: true, greater_than: 0, less_than_or_equal_to: 86_400 },
            allow_nil: true
  validates :official_app_transition_duration_ms,
            numericality: { only_integer: true, greater_than_or_equal_to: 0, less_than_or_equal_to: 10_000 },
            allow_nil: true
  validates :official_app_transition_style,
            inclusion: { in: SavedBroadcastPlaylist::TRANSITION_STYLES },
            allow_blank: true
  validates :official_app_rotation_trigger,
            inclusion: { in: %w[time_interval video_end] },
            allow_blank: true
  validates :official_app_switch_interval_unit,
            inclusion: { in: %w[seconds minutes] },
            allow_blank: true
  validates :official_app_page_duration_unit,
            inclusion: { in: %w[seconds minutes] },
            allow_blank: true
  validates :tv_power_on_time,
            format: {
              with: /\A([01]\d|2[0-3]):[0-5]\d\z/,
              message: "deve estar no formato HH:MM"
            },
            allow_blank: true
  validates :tv_power_off_time,
            format: {
              with: /\A([01]\d|2[0-3]):[0-5]\d\z/,
              message: "deve estar no formato HH:MM"
            },
            allow_blank: true
  before_validation :normalize_playback_app_type
  validate :normalize_tv_disabled_weekdays
  validate :official_app_browser_settings_consistency

  def video_url
    Rails.application.routes.url_helpers.rails_blob_path(video, only_path: true) if video.attached?
  end

  def control_method_summary
    TvDeviceService.new(self).control_mode_label
  end

  def adb_device?
    tv_device_type_android_tv? || tv_device_type_fire_tv?
  end

  def official_app_version_known?
    app_version_code.present?
  end

  def official_app_outdated?
    official_app_version_known? && app_version_code < OFFICIAL_APP_CURRENT_VERSION_CODE
  end

  def tv_device_type_label
    return "Android TV" if tv_device_type_android_tv?
    return "Fire Stick" if tv_device_type_fire_tv?

    I18n.t("Normal")
  end

  def adb_target_host
    adb_host.presence || tv_ip
  end

  def tv_online?
    TvDeviceService.new(self).online?
  end

  def tv_power_status
    if app_screen_power_status.present?
      return app_screen_power_status
    end
    return :on if app_player_online?
    return :unavailable if adb_target_host.blank?

    tv_online? ? :on : :off
  end

  def app_screen_power_status
    return nil unless has_attribute?(:app_screen_on)
    return nil if app_screen_status_updated_at.blank?
    return nil if app_screen_status_updated_at < APP_PLAYER_PRESENCE_TTL.ago

    app_screen_on? ? :on : :off
  end

  def tv_power_status_label
    case tv_power_status
    when :on
      "Ligada"
    when :off
      "Desligada"
    else
      "Sem IP"
    end
  end

  def mobile_tv_power_status
    tv_power_status
  end

  def synced_tv_volume_percent
    return tv_volume_percent.to_i.clamp(0, 100) unless adb_device?
    return tv_volume_percent.to_i.clamp(0, 100) if tv_volume_synced_at.present? && tv_volume_synced_at > 30.seconds.ago

    current_volume = TvDeviceService.new(self).current_volume_percent
    if current_volume.present?
      update_columns(tv_volume_percent: current_volume, tv_volume_synced_at: Time.current) if persisted?
      current_volume
    else
      tv_volume_percent.to_i.clamp(0, 100)
    end
  end

  def tv_power_status_class
    case tv_power_status
    when :on
      "broadcasts-status--online"
    when :off
      "broadcasts-status--offline"
    else
      "broadcasts-status--neutral"
    end
  end

  def app_device_matches?(device_token)
    device_token.present? && app_device_token.present? && ActiveSupport::SecurityUtils.secure_compare(app_device_token, device_token)
  end

  def bind_app_device!(device_token:, seen_at: Time.zone.now)
    return if device_token.blank?

    updates = {
      app_device_token: device_token,
      app_device_last_seen_at: seen_at
    }
    updates[:app_device_registered_at] = seen_at if app_device_registered_at.blank?
    update_columns(updates)
  end

  def update_mobile_presence!(presence_status:, seen_at: Time.zone.now, screen_on: nil, playlist_item_id: nil, position_ms: nil)
    normalized_status = presence_status.to_s.strip
    return false unless %w[online offline playing error].include?(normalized_status)

    updates = {
      app_player_presence_status: normalized_status,
      app_player_presence_updated_at: seen_at,
      app_device_last_seen_at: seen_at
    }
    unless screen_on.nil?
      updates[:app_screen_on] = ActiveModel::Type::Boolean.new.cast(screen_on)
      updates[:app_screen_status_updated_at] = seen_at
    end

    if normalized_status == "playing" && playlist_item_id.present?
      item_id = playlist_items.where(id: playlist_item_id).pick(:id)
      if item_id.present?
        updates[:current_player_playlist_item_id] = item_id
        updates[:current_player_playlist_item_updated_at] = seen_at
        updates[:current_player_position_ms] = position_ms.to_i.clamp(0, 86_400_000) if position_ms.present?
      end
    elsif normalized_status != "playing"
      updates[:current_player_playlist_item_id] = nil
      updates[:current_player_playlist_item_updated_at] = nil
      updates[:current_player_position_ms] = 0 if has_attribute?(:current_player_position_ms)
    end

    update_columns(updates)
  end

  def app_player_online?
    %w[online playing error].include?(effective_app_player_presence_status)
  end

  def app_player_playing?
    effective_app_player_presence_status == "playing"
  end

  def effective_production_status
    status.to_s == "running" || app_player_playing? ? "running" : "stopped"
  end

  def app_player_presence_label
    app_player_online? ? "App online" : "App offline"
  end

  def effective_app_player_presence_status
    normalized_status = app_player_presence_status.to_s
    return "offline" unless %w[online playing error].include?(normalized_status)
    return "offline" if app_player_presence_updated_at.blank?
    return "offline" if app_player_presence_updated_at < APP_PLAYER_PRESENCE_TTL.ago

    normalized_status
  end

  def current_stream_url(streaming_configuration = nil)
    filename = current_stream_filename
    return stream_url if filename.blank?

    streaming_configuration ||= StreamingConfiguration.find_by(id: 1)
    return stream_url if streaming_configuration.blank? || streaming_configuration.server_ip.blank? || streaming_configuration.port.blank?

    "http://#{streaming_configuration.server_ip}:#{streaming_configuration.port}/hls/#{filename}"
  end

  def current_player_playlist_item
    return nil unless app_player_playing?
    return nil if current_player_playlist_item_id.blank?
    return nil if current_player_playlist_item_updated_at.blank?
    return nil if current_player_playlist_item_updated_at < APP_PLAYER_PRESENCE_TTL.ago

    playlist_items.includes(media_attachment: :blob).find_by(id: current_player_playlist_item_id)
  end

  def ffmpeg_stream_url(streaming_configuration = nil)
    return unless uses_ffmpeg_streaming?

    current_stream_url(streaming_configuration)
  end

  def app_playback_url(streaming_configuration = nil)
    if playlist_enabled?
      first_playlist_item_url(streaming_configuration) || current_stream_url(streaming_configuration)
    elsif official_app_direct_video_playback_enabled?
      official_app_direct_video_url(streaming_configuration)
    else
      current_stream_url(streaming_configuration)
    end
  end

  def current_stream_filename
    return if stream_url.blank?

    URI.parse(stream_url).path.split("/").last
  rescue URI::InvalidURIError
    stream_url.to_s.split("/").last
  end

  def tv_power_schedule_configured?
    tv_power_on_time.present? || tv_power_off_time.present? || normalized_tv_disabled_weekdays.any?
  end

  def tv_power_schedule_summary
    parts = []
    parts << "Liga #{tv_power_on_time}" if tv_power_on_time.present?
    parts << "Desliga #{tv_power_off_time}" if tv_power_off_time.present?

    if normalized_tv_disabled_weekdays.any?
      disabled_labels = WEEKDAY_OPTIONS.filter_map do |label, value|
        label if normalized_tv_disabled_weekdays.include?(value)
      end
      parts << "Dias off: #{disabled_labels.join(', ')}" if disabled_labels.any?
    end

    parts.presence&.join(" | ") || "Sem agendamento de energia"
  end

  def official_app_browser_rotation_enabled?
    playback_app_type_official_app? && official_app_web_enabled? && official_app_page_url.present?
  end

  def official_app_web_enabled?
    ActiveModel::Type::Boolean.new.cast(self[:official_app_web_enabled])
  end

  def official_app_web_only?
    ActiveModel::Type::Boolean.new.cast(self[:official_app_web_only])
  end

  def official_app_direct_video_playback_enabled?
    playback_app_type_official_app? && (video.attached? || playlist_enabled?)
  end

  def uses_ffmpeg_streaming?
    !official_app_direct_video_playback_enabled?
  end

  def preview_playback_mode_label
    return "Página web no app oficial" if official_app_browser_rotation_enabled? && official_app_web_only?
    return "Playlist" if playlist_enabled?

    official_app_direct_video_playback_enabled? ? "Video direto" : "FFmpeg / HLS"
  end

  def official_app_browser_rotation_summary
    return "Sem alternancia web no app oficial" unless official_app_browser_rotation_enabled?

    trigger_summary =
      if official_app_rotation_trigger.to_s == "video_end"
        "abre ao terminar o video"
      else
        "abre a cada #{official_app_switch_interval_seconds.presence || 300}s"
      end

    playback_summary =
      if official_app_direct_video_playback_enabled?
        "video direto do servidor"
      else
        "stream HLS/ffmpeg"
      end

    "#{trigger_summary}, exibe por #{official_app_page_duration_seconds.presence || 15}s, " \
      "transicao #{official_app_transition_style.presence || 'blur'}, #{playback_summary}"
  end

  def official_app_payload(streaming_configuration = nil, include_login: false)
    enabled = official_app_browser_rotation_enabled?

    {
      enabled: enabled,
      web_only: enabled && official_app_web_only?,
      page_url: enabled ? official_app_page_url : nil,
      login: enabled && official_app_login_enabled? && include_login ? {
        username: official_app_login_username,
        password: official_app_login_password
      } : nil,
      rotation_trigger: enabled ? (official_app_rotation_trigger.presence || "time_interval") : nil,
      switch_interval_unit: official_app_switch_interval_unit.presence || "minutes",
      switch_interval_seconds: official_app_switch_interval_seconds.presence || 300,
      page_duration_unit: official_app_page_duration_unit.presence || "seconds",
      page_duration_seconds: official_app_page_duration_seconds.presence || 15,
      transition_style: official_app_transition_style.presence || "blur",
      transition_duration_ms: official_app_transition_duration_ms.presence || 900,
      direct_video_url: video.attached? ? official_app_direct_video_url(streaming_configuration) : first_playlist_video_url(streaming_configuration)
    }
  end

  def playlist_enabled?
    playlist_items.any?
  end

  def mobile_playlist_payload(streaming_configuration = nil)
    return [] unless playlist_enabled?

    playlist_items.includes(media_attachment: :blob).filter_map do |item|
      next unless item.media.attached?

      {
        id: item.id,
        type: item.media_type,
        name: item.media.filename.to_s,
        url: mobile_playlist_playback_url(item, streaming_configuration),
        duration_seconds: item.duration_seconds,
        video_duration_mode: item.video_duration_mode.presence || "image_duration",
        transition_style: item.transition_style.presence || "fade",
        transition_duration_ms: item.transition_duration_ms.presence || 600,
        content_type: item.media.blob.content_type
      }
    end
  end

  def mobile_playlist_version
    latest_item = playlist_items.maximum(:updated_at)
    [updated_at, latest_item].compact.max&.iso8601
  end

  def official_app_direct_video_url(streaming_configuration = nil)
    return unless video.attached?

    streaming_configuration ||= StreamingConfiguration.find_by(id: 1)
    host = streaming_configuration&.server_ip.presence || "127.0.0.1"

    Rails.application.routes.url_helpers.mobile_prepared_video_broadcast_url(
      self,
      host: host,
      protocol: "http",
      port: 3000,
      v: cache_key_with_version
    )
  end

  def mobile_playlist_item_url(item, streaming_configuration = nil)
    streaming_configuration ||= StreamingConfiguration.find_by(id: 1)
    host = streaming_configuration&.server_ip.presence || "127.0.0.1"

    Rails.application.routes.url_helpers.mobile_playlist_item_broadcast_url(
      self,
      item,
      host: host,
      protocol: "http",
      port: 3000,
      v: item.updated_at.to_i
    )
  end

  def mobile_playlist_playback_url(item, streaming_configuration = nil)
    if item.video? && video.attached? && item.media.attached? && item.media.blob_id == video.blob_id
      return official_app_direct_video_url(streaming_configuration)
    end

    mobile_playlist_item_url(item, streaming_configuration)
  end

  def first_playlist_video_url(streaming_configuration = nil)
    item = playlist_items.includes(media_attachment: :blob).detect(&:video?)
    return if item.blank?

    mobile_playlist_playback_url(item, streaming_configuration)
  end

  def first_playlist_item_url(streaming_configuration = nil)
    item = playlist_items.includes(media_attachment: :blob).first
    return if item.blank?

    mobile_playlist_playback_url(item, streaming_configuration)
  end

  def register_tv_power_action!(action:, status:, message:, at: Time.zone.now)
    update_columns(
      last_tv_power_action: action,
      last_tv_power_action_status: status,
      last_tv_power_action_message: message,
      last_tv_power_action_at: at
    )
  end

  def last_tv_power_action_summary
    return "Nenhuma tentativa registrada" if last_tv_power_action_at.blank?

    action_label = case last_tv_power_action
                   when "power_on" then "Ligar"
                   when "power_off" then "Desligar"
                   else "Acao"
                   end

    status_label = case last_tv_power_action_status
                   when "success" then "Sucesso"
                   when "failed" then "Falha"
                   when "skipped" then "Ignorado"
                   when "error" then "Erro"
                   else last_tv_power_action_status.to_s.humanize
                   end

    action_at = formatted_datetime_label(last_tv_power_action_at)
    [action_label, status_label, action_at].compact.join(" | ")
  end

  def tv_power_on_due?(now = Time.zone.now)
    schedule_due?(tv_power_on_time, last_tv_power_on_at, now)
  end

  def tv_power_off_due?(now = Time.zone.now)
    schedule_due?(tv_power_off_time, last_tv_power_off_at, now)
  end

  def tv_schedule_blocked_today?
    normalized_tv_disabled_weekdays.include?(Time.zone.now.wday.to_s)
  end

  def tv_schedule_blocked_today_label
    today_label = WEEKDAY_OPTIONS.find { |_label, value| value == Time.zone.now.wday.to_s }&.first || "Hoje"
    "O agendamento de energia esta bloqueado hoje (#{today_label}) porque esse dia esta marcado para manter a TV desligada."
  end

  def tv_power_on_time_input_value
    normalize_time_input(tv_power_on_time)
  end

  def tv_power_off_time_input_value
    normalize_time_input(tv_power_off_time)
  end

  def generate_command(show_widgets = false)
    video_path = ActiveStorage::Blob.service.path_for(video.key)
    stream_filename = current_stream_filename.presence || default_stream_filename
    segment_pattern = default_segment_pattern(stream_filename)

    if orientation == "landscape"
      scale_filter = "scale=1366:768,setdar=16/9"
    elsif orientation == "portrait_inverted"
      scale_filter = "scale=768:1366,setdar=9/16,transpose=2"
    else
      scale_filter = "scale=768:1366,setdar=9/16,transpose=1"
    end

    base_command = "ffmpeg -stream_loop -1 -re -i #{video_path} -vf \"#{scale_filter},setsar=1\" \
-c:v libx264 -preset fast -c:a aac -b:a 192k -f hls \
-hls_time 8 -hls_list_size 0 \
-hls_segment_filename \"C:/nginx/temp/hls/#{segment_pattern}\" \
\"C:/nginx/temp/hls/#{stream_filename}\""

    if show_widgets
      overlay_filter = "[1:v]scale=768:300,format=yuva420p,colorchannelmixer=aa=0.9[overlay]; \
[bg][overlay]overlay=x=0:y=0"
      base_command = "ffmpeg -stream_loop -1 -re -i #{video_path} -i http://localhost:8080/hls/widgets.m3u8 \
-filter_complex \"[0:v]#{scale_filter},setsar=1[bg];#{overlay_filter}\" \
-c:v libx264 -preset fast -c:a aac -b:a 192k -f hls \
-hls_time 8 -hls_list_size 0 \
-hls_segment_filename \"C:/nginx/temp/hls/#{segment_pattern}\" \
\"C:/nginx/temp/hls/#{stream_filename}\""
    end

    base_command
  end

  private

  def default_stream_filename
    identifier = id.presence || (Broadcast.count + 1)
    "stream#{identifier}.m3u8"
  end

  def default_segment_pattern(stream_filename)
    suffix = stream_filename.to_s.sub(/\Astream/, "").sub(/\.m3u8\z/, "")
    suffix = id.presence || (Broadcast.count + 1) if suffix.blank?
    "segment_#{suffix}_%03d.ts"
  end

  def schedule_due?(time_value, last_run_at, now)
    return false if time_value.blank?

    scheduled_at = scheduled_time_for(now, time_value)
    return false if scheduled_at.blank?
    return false if now < scheduled_at
    return false if last_run_at.present? && last_run_at.to_date == now.to_date && last_run_at >= scheduled_at

    true
  end

  def scheduled_time_for(now, time_value)
    hours, minutes = normalize_time_input(time_value).to_s.split(":").map(&:to_i)
    return if hours.nil? || minutes.nil?

    now.change(hour: hours, min: minutes, sec: 0)
  end

  def normalize_tv_disabled_weekdays
    self.tv_disabled_weekdays = normalized_tv_disabled_weekdays
  end

  def official_app_browser_settings_consistency
    return unless playback_app_type_official_app?
    return unless official_app_web_enabled?
    return if official_app_page_url.blank?
    return if official_app_web_only?

    if official_app_rotation_trigger.to_s != "video_end" && official_app_switch_interval_seconds.blank?
      errors.add(:official_app_switch_interval_seconds, "deve ser informado quando houver pagina web no app oficial")
    end

    if official_app_page_duration_seconds.blank?
      errors.add(:official_app_page_duration_seconds, "deve ser informado quando houver pagina web no app oficial")
    end

    if official_app_rotation_trigger.to_s == "video_end" && !video.attached? && !playlist_items.any?
      errors.add(:video, "deve estar anexado ou uma playlist deve estar selecionada para usar a troca ao terminar a reproducao")
    end
  end

  def normalize_time_input(value)
    return if value.blank?
    return value.strftime("%H:%M") if value.is_a?(Time) || value.is_a?(DateTime) || value.is_a?(ActiveSupport::TimeWithZone)

    value.to_s.first(5)
  end

  def formatted_datetime_label(value)
    return if value.blank?
    return value.strftime("%d/%m %H:%M") if value.respond_to?(:strftime)

    Time.zone.parse(value.to_s)&.strftime("%d/%m %H:%M")
  rescue ArgumentError, TypeError
    value.to_s
  end

  def normalized_tv_disabled_weekdays
    Array(tv_disabled_weekdays).map(&:to_s).select { |value| value.match?(/\A[0-6]\z/) }.uniq
  end

  def normalize_playback_app_type
    self.playback_app_type = "official_app" unless %w[official_app generic_app].include?(playback_app_type.to_s)
  end
end
