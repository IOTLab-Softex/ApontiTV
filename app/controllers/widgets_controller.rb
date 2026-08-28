class WidgetsController < ApplicationController
  skip_before_action :authenticate_user!, only: [:temperature]
  skip_before_action :check_license, only: [:temperature]

  require "json"
  require "net/http"

  layout false

  def show
  end

  def calendario
  end

  def temperature
    config = StreamingConfiguration.find_by(id: 1)
    api_url = config&.widget_bar_weather_api_url.presence || "https://labs.aponti.org.br/api/current_data?station=estacao_01"
    uri = URI.parse(api_url)
    response = Net::HTTP.start(uri.host, uri.port, use_ssl: uri.scheme == "https", read_timeout: 6, open_timeout: 4) do |http|
      http.get(uri.request_uri)
    end

    raw_data = JSON.parse(response.body)
    data = raw_data["data"].is_a?(Hash) ? raw_data["data"] : raw_data
    temperature_data = data["temperatura"].is_a?(Hash) ? data["temperatura"] : {}
    uv_data = data["indice_uv"].is_a?(Hash) ? data["indice_uv"] : data["uv"]
    uv_data = {} unless uv_data.is_a?(Hash)
    sky_data = data["ceu"].is_a?(Hash) ? data["ceu"] : {}
    icon_info = data.dig("raw", "accuweather", "icon_info")
    icon_info = {} unless icon_info.is_a?(Hash)

    temperature = temperature_data["value"] || temperature_data["valor"]
    unit = temperature_data["unit"].presence || "°C"

    render json: {
      temperature: temperature.present? ? "#{temperature.to_f.round}#{unit}" : "--",
      status: sky_data["weather_text"].presence || temperature_data["status"].to_s,
      uv_index: uv_data["value"] || uv_data["valor"],
      uv_status: uv_data["status"].to_s,
      condition: icon_info["cat"].presence ||
        sky_data["cloud_status"].presence ||
        sky_data["weather_text"].presence ||
        data.dig("weather_cond", "value").to_s.presence ||
        "clouds"
    }
  rescue StandardError => error
    Rails.logger.error("Erro ao buscar temperatura: #{error.message}")
    render json: { temperature: "--", status: "", uv_index: nil, uv_status: "", condition: "clouds" }
  end
end
