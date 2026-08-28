class StreamingConfigurationsController < ApplicationController
  require "tempfile"

  WEATHER_ASSET_ATTACHMENTS = %i[
    widget_weather_default_image
    widget_weather_sun_image
    widget_weather_rain_image
    widget_weather_cloud_image
    widget_weather_storm_image
    widget_weather_default_assets
    widget_weather_sun_assets
    widget_weather_rain_assets
    widget_weather_cloud_assets
    widget_weather_storm_assets
  ].freeze

  MULTIPLE_WEATHER_ASSET_ATTACHMENTS = %i[
    widget_weather_default_assets
    widget_weather_sun_assets
    widget_weather_rain_assets
    widget_weather_cloud_assets
    widget_weather_storm_assets
  ].freeze

  before_action :set_streaming_configuration, only: %i[show edit update destroy]

  def update
    previous_widgets_state = @streaming_configuration.widgets_for_ffmpeg?
    configuration_params = streaming_configuration_params
    weather_asset_ids_to_delete = extract_weather_asset_ids_to_delete(configuration_params)
    weather_asset_uploads = extract_weather_asset_uploads(configuration_params)

    respond_to do |format|
      if @streaming_configuration.update(configuration_params)
        purge_weather_assets!(weather_asset_ids_to_delete)
        attach_weather_asset_uploads!(weather_asset_uploads)
        convert_weather_asset_videos_to_gif!
        current_widgets_state = @streaming_configuration.widgets_for_ffmpeg?
        Rails.logger.info "Widgets FFmpeg antes: #{previous_widgets_state}, agora: #{current_widgets_state}"

        if current_widgets_state && !previous_widgets_state
          start_overlay_script
        elsif !current_widgets_state && previous_widgets_state
          stop_overlay_script
        end

        format.html { redirect_to broadcasts_path, notice: "Configuracao atualizada com sucesso." }
        format.json { render :show, status: :ok, location: @streaming_configuration }
      else
        format.html { render :edit, status: :unprocessable_entity }
        format.json { render json: @streaming_configuration.errors, status: :unprocessable_entity }
      end
    end
  end

  private

  def start_overlay_script
    command = "start /B /MIN node app/javascript/generate_overlay.js start"

    begin
      system(command)
      Rails.logger.info "Processo iniciado com comando: #{command}"
    rescue StandardError => e
      Rails.logger.error "Erro ao iniciar o processo: #{e.message}"
    end
  end

  def stop_overlay_script
    command = "start /B /MIN node app/javascript/generate_overlay.js stop"

    begin
      system(command)
      Rails.logger.info "Comando para parar o overlay executado com sucesso."

      if @streaming_configuration.pid
        system("taskkill /PID #{@streaming_configuration.pid} /F")
        Rails.logger.info "Processo com PID #{@streaming_configuration.pid} encerrado com sucesso."
        @streaming_configuration.update(pid: nil)
      else
        Rails.logger.warn "Nenhum processo ativo encontrado para encerrar."
      end
    rescue StandardError => e
      Rails.logger.error "Erro ao parar o processo de overlay: #{e.message}"
    end
  end

  def set_streaming_configuration
    @streaming_configuration = StreamingConfiguration.find(params[:id])
  end

  def extract_weather_asset_uploads(configuration_params)
    MULTIPLE_WEATHER_ASSET_ATTACHMENTS.each_with_object({}) do |attachment_name, uploads|
      files = Array(configuration_params.delete(attachment_name)).reject(&:blank?)
      uploads[attachment_name] = files if files.any?
    end
  end

  def extract_weather_asset_ids_to_delete(configuration_params)
    Array(configuration_params.delete(:remove_widget_weather_asset_ids)).reject(&:blank?)
  end

  def purge_weather_assets!(attachment_ids)
    return if attachment_ids.blank?

    allowed_attachment_ids = WEATHER_ASSET_ATTACHMENTS.flat_map do |attachment_name|
      weather_asset_records_for(attachment_name).map { |attachment_record| attachment_record.id.to_s }
    end
    ids_to_purge = attachment_ids.map(&:to_s) & allowed_attachment_ids
    return if ids_to_purge.empty?

    ActiveStorage::Attachment.includes(:blob).where(id: ids_to_purge).find_each do |attachment|
      blob = attachment.blob
      attachment.destroy
      blob.purge if blob.attachments.reload.none?
    rescue StandardError => error
      Rails.logger.warn("Falha ao excluir variacao de clima #{attachment.id}: #{error.class} - #{error.message}")
    end

    @streaming_configuration.reload
  end

  def attach_weather_asset_uploads!(weather_asset_uploads)
    weather_asset_uploads.each do |attachment_name, files|
      @streaming_configuration.public_send(attachment_name).attach(files)
    end
  end

  def convert_weather_asset_videos_to_gif!
    WEATHER_ASSET_ATTACHMENTS.each do |attachment_name|
      weather_asset_records_for(attachment_name).each do |attachment_record|
        next unless attachment_record.blob.content_type.to_s.start_with?("video/")

        convert_weather_video_attachment_to_gif!(attachment_name, attachment_record)
      end
    end
  end

  def weather_asset_records_for(attachment_name)
    attachment = @streaming_configuration.public_send(attachment_name)
    return [] unless attachment.attached?
    return attachment.attachments.to_a if attachment.respond_to?(:attachments)

    [attachment.attachment].compact
  end

  def convert_weather_video_attachment_to_gif!(attachment_name, attachment_record)
    input = Tempfile.new(["aponti_weather_asset", File.extname(attachment_record.filename.to_s)])
    output = Tempfile.new(["aponti_weather_asset", ".gif"])
    input.binmode
    output.binmode

    input.write(attachment_record.blob.download)
    input.flush
    output.close

    command = [
      ffmpeg_executable,
      "-y",
      "-i", input.path,
      "-vf", "fps=18,scale=420:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=96[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5",
      "-loop", "0",
      output.path
    ]

    unless system(*command)
      Rails.logger.error "Falha ao converter video de clima para GIF: #{attachment_record.filename}"
      attachment_record.purge
      return
    end

    File.open(output.path, "rb") do |file|
      @streaming_configuration.public_send(attachment_name).attach(
        io: file,
        filename: "#{File.basename(attachment_record.filename.to_s, '.*')}.gif",
        content_type: "image/gif"
      )
    end
    attachment_record.purge if @streaming_configuration.public_send(attachment_name).respond_to?(:attachments)
  ensure
    input&.close!
    output&.close!
  end

  def ffmpeg_executable
    candidates = [
      "C:/nginx/ffmpeg/bin/ffmpeg.exe",
      "C:/nginx/ffmpeg/bin/ffmpeg",
      "ffmpeg"
    ]

    candidates.find { |candidate| candidate == "ffmpeg" || File.exist?(candidate) } || "ffmpeg"
  end

  def streaming_configuration_params
    params.require(:streaming_configuration).permit(
      :widgets,
      :server_ip,
      :port,
      :server_name,
      :pid,
      :google_oauth_enabled,
      :google_client_id,
      :google_client_secret,
      :widgets_ffmpeg_enabled,
      :widgets_official_app_enabled,
      :widget_bar_style,
      :widget_bar_color,
      :widget_bar_opacity,
      :widget_bar_blur_enabled,
      :widget_bar_behavior,
      :widget_bar_animation,
      :widget_bar_layout_mode,
      :widget_bar_edge_spacing,
      :widget_bar_show_seconds,
      :widget_bar_show_unit,
      :widget_bar_appear_seconds,
      :widget_bar_appear_unit,
      :widget_bar_hide_seconds,
      :widget_bar_hide_unit,
      :widget_bar_weather_api_url,
      :widget_bar_weather_test_condition,
      :widget_bar_content_mode,
      :widget_weather_default_image,
      :widget_weather_sun_image,
      :widget_weather_rain_image,
      :widget_weather_cloud_image,
      :widget_weather_storm_image,
      widget_weather_default_assets: [],
      widget_weather_sun_assets: [],
      widget_weather_rain_assets: [],
      widget_weather_cloud_assets: [],
      widget_weather_storm_assets: [],
      remove_widget_weather_asset_ids: []
    )
  end
end
