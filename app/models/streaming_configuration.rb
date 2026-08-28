require 'resolv'
require 'uri'

class StreamingConfiguration < ApplicationRecord
    has_one_attached :widget_weather_default_image
    has_one_attached :widget_weather_sun_image
    has_one_attached :widget_weather_rain_image
    has_one_attached :widget_weather_cloud_image
    has_one_attached :widget_weather_storm_image
    has_many_attached :widget_weather_default_assets
    has_many_attached :widget_weather_sun_assets
    has_many_attached :widget_weather_rain_assets
    has_many_attached :widget_weather_cloud_assets
    has_many_attached :widget_weather_storm_assets

    # Validações
    validates :server_ip, presence: true, format: { with: Resolv::IPv4::Regex, message: "deve ser um IP válido" }
    validates :port, numericality: { only_integer: true, greater_than: 0, less_than: 65536 }
  
    # Métodos adicionais
    def connection_url
      "http://#{server_ip}:#{port}"
    end

    def google_oauth_ready?
      google_oauth_enabled? && google_client_id.present? && google_client_secret.present?
    end

    def widgets_for_ffmpeg?
      widgets? && widgets_ffmpeg_enabled?
    end

    def widgets_for_official_app?
      widgets? && widgets_official_app_enabled?
    end

    def official_app_widget_bar_payload(base_url = nil)
      {
        enabled: widgets_for_official_app?,
        style: widget_bar_style.presence || "transparent_blur",
        color: widget_bar_color.presence || "#0b1020",
        opacity: widget_bar_opacity.to_i.clamp(0, 100),
        blur_enabled: widget_bar_blur_enabled?,
        behavior: widget_bar_behavior.presence || "fixed",
        animation: widget_bar_animation.presence || "slide",
        layout_mode: widget_bar_layout_mode.presence || "overlay",
        edge_spacing: widget_bar_edge_spacing.to_i.clamp(0, 120),
        show_unit: widget_bar_show_unit.presence || "seconds",
        show_value: widget_bar_show_seconds.to_i.clamp(1, 120),
        show_seconds: widget_bar_show_duration_seconds,
        appear_seconds: widget_bar_appear_duration_seconds,
        hide_seconds: widget_bar_hide_duration_seconds,
        weather_api_url: widget_bar_weather_api_url.presence || "https://labs.aponti.org.br/api/current_data?station=estacao_01",
        weather_test_condition: widget_bar_weather_test_condition.presence || "real",
        content_mode: widget_bar_content_mode.presence || "time_weather",
        weather_assets: widget_weather_assets_payload(base_url)
      }
    end

    def widget_weather_assets_payload(base_url = nil)
      {
        default: widget_weather_asset_urls(widget_weather_default_image, widget_weather_default_assets, base_url),
        sun: widget_weather_asset_urls(widget_weather_sun_image, widget_weather_sun_assets, base_url),
        rain: widget_weather_asset_urls(widget_weather_rain_image, widget_weather_rain_assets, base_url),
        cloud: widget_weather_asset_urls(widget_weather_cloud_image, widget_weather_cloud_assets, base_url),
        storm: widget_weather_asset_urls(widget_weather_storm_image, widget_weather_storm_assets, base_url)
      }.transform_values(&:compact).select { |_key, urls| urls.any? }
    end

    def widget_weather_asset_urls(single_attachment, multiple_attachments, base_url = nil)
      urls = []
      urls << widget_weather_asset_url(single_attachment, base_url) if single_attachment.attached?
      multiple_attachments.each do |attachment|
        urls << widget_weather_blob_url(attachment.blob, base_url)
      end
      urls.uniq
    end

    def widget_bar_show_duration_seconds
      value = widget_bar_show_seconds.to_i.clamp(1, 120)
      unit = widget_bar_show_unit.presence || "seconds"
      unit == "minutes" ? (value * 60).clamp(1, 7200) : value
    end

    def widget_bar_appear_duration_seconds
      duration_seconds_from(widget_bar_appear_seconds, widget_bar_appear_unit, allow_zero: true)
    end

    def widget_bar_hide_duration_seconds
      duration_seconds_from(widget_bar_hide_seconds, widget_bar_hide_unit, allow_zero: true)
    end

    def duration_seconds_from(value, unit, allow_zero: false)
      minimum = allow_zero ? 0 : 1
      numeric_value = value.to_i.clamp(minimum, 120)
      return 0 if allow_zero && numeric_value.zero?

      unit.to_s == "minutes" ? (numeric_value * 60).clamp(minimum, 7200) : numeric_value
    end

    def widget_weather_asset_url(attachment, base_url = nil)
      return unless attachment.attached?

      widget_weather_blob_url(attachment.blob, base_url)
    end

    def widget_weather_blob_url(blob, base_url = nil)
      host = base_url.presence || "http://#{server_ip}:3000"
      uri = URI.parse(host)
      Rails.application.routes.url_helpers.rails_blob_url(
        blob,
        host: uri.host,
        port: uri.port,
        protocol: uri.scheme
      )
    rescue URI::InvalidURIError
      Rails.application.routes.url_helpers.rails_blob_url(blob, host: host)
    end

    def self.google_oauth_ready?
      find_by(id: 1)&.google_oauth_ready? || false
    rescue ActiveRecord::StatementInvalid, ActiveRecord::NoDatabaseError
      false
    end
  end
  
