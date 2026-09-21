class BroadcastsController < ApplicationController
  include Rails.application.routes.url_helpers
  require "shellwords"
  require "fileutils"
  require "socket"

  FFMPEG_CANDIDATE_PATHS = [
    "C:/nginx/ffmpeg/bin/ffmpeg.exe",
    "C:/nginx/ffmpeg/bin/ffmpeg",
    "ffmpeg"
  ].freeze
  NGINX_CANDIDATE_PATHS = [
    "C:/nginx/nginx.exe"
  ].freeze
  PLAYLIST_SYNC_DRIFT_THRESHOLD_MS = 1_500
  PLAYLIST_SYNC_RESYNC_COOLDOWN = 10.seconds

  before_action :authenticate_user!
  skip_before_action :verify_authenticity_token
  skip_before_action :authenticate_user!, only: [:mobile_index, :mobile_status, :presentation_status, :mobile_presence, :mobile_player_status, :presentation_command, :request_adb_authorization, :mobile_thumbnail, :mobile_video, :mobile_prepared_video, :mobile_playlist_item]
  before_action :set_broadcast, only: [:show, :edit, :update, :destroy, :start, :stop, :power_on_tv, :power_off_tv, :volume_up_tv, :volume_down_tv, :set_volume_tv, :mute_tv, :update_power_schedule, :request_adb_authorization, :set_android_launcher, :remove_android_launcher, :open_official_app, :update_official_app, :toggle_presentation_mode, :toggle_forecast_widget, :presentation_command, :preview_stream, :mobile_status, :presentation_status, :mobile_presence, :mobile_player_status, :mobile_thumbnail, :mobile_video, :mobile_prepared_video, :mobile_playlist_item]
  before_action :require_admin_for_broadcast_edit, only: [:edit, :update, :toggle_forecast_widget]
  before_action :set_available_video_blobs, only: [:new, :edit, :create, :update]
  before_action :set_playlist_library, only: [:index, :new, :edit, :create, :update]

  def index
    @broadcasts = Broadcast.includes(
      video_attachment: :blob,
      playlist_items: { media_attachment: :blob }
    ).to_a
    @streaming_configuration = StreamingConfiguration.find_by(id: 1)
    @desktop_groups = DesktopGroup.order(:name).to_a
    @desktop_agent_counts_by_group = DesktopAgent.enabled.where(status: "approved").group(:desktop_group_id).count
    @desktop_agent_total = @desktop_agent_counts_by_group.values.sum
  end

  def new
    @broadcast = Broadcast.new
  end

  def show
    @tv_online = @broadcast.tv_online?
  end

  def create
    @broadcast = Broadcast.new(broadcast_params)
    selected_playlist = selected_broadcast_playlist
    attach_selected_video_blob(@broadcast)
    if selected_playlist.present?
      blobs = selected_playlist.items.map(&:media_blob)
      publish_playlist_to_broadcast(@broadcast, blobs, playlist: selected_playlist)
    end
    streaming_configuration = StreamingConfiguration.find_by(id: 1)

    if streaming_configuration
      server_ip = streaming_configuration.server_ip
      port = streaming_configuration.port
    end

    @stream_url = "http://#{server_ip}:#{port}/hls/stream#{Broadcast.count + 1}.m3u8"

    unless @broadcast.video.attached? || @broadcast.playlist_items.any?
      @broadcast.errors.add(:video, "precisa ser enviado ou selecionado da biblioteca")
      render :new, status: :unprocessable_entity
      return
    end

    if @broadcast.save
      @broadcast.update(
        stream_url: @stream_url,
        command: @broadcast.generate_command(@broadcast.show_widgets)
      )
      redirect_with_android_tv_configuration(
        success_message: "Broadcast was successfully created."
      )
    else
      render :new, status: :unprocessable_entity
    end
  end

  def edit
    @schedules = @broadcast.schedules.map do |schedule|
      {
        id: schedule.id,
        start: format_schedule_date(schedule.start_date),
        end: format_schedule_date(schedule.end_date),
        video: schedule.video.attached? ? schedule.video.filename.to_s : nil
      }
    end
  end

  def update
    was_running = @broadcast.status == "running"
    selected_playlist = selected_broadcast_playlist
    save_success = false

    ActiveRecord::Base.transaction do
      @broadcast.assign_attributes(broadcast_params)
      attach_selected_video_blob(@broadcast)
      if selected_playlist.present?
        blobs = selected_playlist.items.map(&:media_blob)
        publish_playlist_to_broadcast(@broadcast, blobs, playlist: selected_playlist)
      end

      unless @broadcast.video.attached? || @broadcast.playlist_items.any?
        @broadcast.errors.add(:video, "precisa ser enviado ou selecionado da biblioteca")
        raise ActiveRecord::Rollback
      end

      unless @broadcast.save
        raise ActiveRecord::Rollback
      end
    end

    if @broadcast.errors.any?
      render :edit, status: :unprocessable_entity
      return
    end

    if @broadcast.persisted? && @broadcast.valid?
      save_success = true
    end

    if save_success
      if @broadcast.video.attached?
        @broadcast.update(command: @broadcast.generate_command(@broadcast.show_widgets))
      else
        @broadcast.update(command: nil)
      end
      restart_notice = restart_broadcast_after_update(@broadcast) if was_running && update_playback_after_save?

      redirect_with_android_tv_configuration(
        success_message: ["Broadcast was successfully updated.", restart_notice].compact.join(" ")
      )
    else
      if @broadcast.errors.blank?
        @broadcast.errors.add(:base, "nao foi possivel salvar as alteracoes")
      end
        render :edit, status: :unprocessable_entity
    end
  rescue Errno::ENOENT => e
    redirect_to broadcasts_path, alert: "Broadcast atualizado, mas nao foi possivel reiniciar automaticamente. Verifique o FFmpeg. Detalhe: #{e.message}"
  rescue StandardError => e
    redirect_to broadcasts_path, alert: "Broadcast atualizado, mas houve erro ao reiniciar automaticamente: #{e.message}"
  end

  def destroy
    unless current_user.admin?
      redirect_to broadcasts_path, alert: "Apenas administradores podem excluir TVs."
      return
    end

    @broadcast.destroy
    redirect_to broadcasts_path, notice: "Broadcast was successfully deleted."
  end

  def events
    events = Broadcast.all.map do |broadcast|
      {
        title: broadcast.name,
        start: broadcast.event_date.iso8601,
        end: (broadcast.event_date + 1.hour).iso8601
      }
    end

    render json: events
  end

  def update_event
    broadcast = Broadcast.find(params[:event_id])

    if broadcast.update(event_date: params[:start])
      render json: { status: "success" }
    else
      render json: { status: "error" }
    end
  end

  def stop_all
    broadcasts = Broadcast.where(id: params[:broadcast_ids])

    broadcasts.each do |broadcast|
      begin
        stop_broadcast_process(broadcast)
      rescue StandardError => e
        flash[:alert] = "Erro ao interromper o broadcast #{broadcast.name}: #{e.message}"
      ensure
        broadcast.update(status: "stopped", process_pid: nil)
      end
    end

    redirect_to broadcasts_path, notice: "Todas as transmissoes foram interrompidas!"
  end

  def start
    unless @broadcast.uses_ffmpeg_streaming?
      @broadcast.update(process_pid: nil, status: "running")
      notify_broadcast_event!(
        @broadcast,
        event_key: "production_started",
        title: "TV em producao",
        message: "#{@broadcast.name} foi iniciada no app oficial.",
        severity: "success"
      )
      redirect_to broadcasts_path, notice: "Broadcast #{@broadcast.name} configurado para video direto no app oficial. FFmpeg nao foi iniciado."
      return
    end

    command = @broadcast.generate_command(@broadcast.show_widgets)
    pid = spawn_broadcast_process(command)
    Process.detach(pid)
    @broadcast.update(command: command, process_pid: pid, status: "running")
    notify_broadcast_event!(
      @broadcast,
      event_key: "production_started",
      title: "TV em producao",
      message: "#{@broadcast.name} iniciou a transmissao.",
      severity: "success"
    )

    redirect_to broadcasts_path, notice: "Iniciando broadcast: #{@broadcast.name}"
  rescue Errno::ENOENT => e
    notify_broadcast_event!(
      @broadcast,
      event_key: "production_start_failed",
      title: "Falha ao iniciar TV",
      message: "#{@broadcast.name}: #{e.message}",
      severity: "error"
    )
    redirect_to broadcasts_path, alert: "Nao foi possivel iniciar o broadcast. Verifique se o FFmpeg esta instalado. Detalhe: #{e.message}"
  end

  def stop
    begin
      stop_broadcast_process(@broadcast)
      flash[:notice] = "Parando broadcast: #{@broadcast.name}"
    rescue StandardError => e
      flash[:alert] = "Erro ao parar o broadcast: #{e.message}"
    ensure
      @broadcast.update(status: "stopped", process_pid: nil)
      notify_broadcast_event!(
        @broadcast,
        event_key: "production_stopped",
        title: "TV parou de produzir",
        message: "#{@broadcast.name} foi parada pelo painel.",
        severity: "warning"
      )
    end

    redirect_to broadcasts_path
  end

  def execute
    broadcast_ids = params[:broadcast_ids]

    if broadcast_ids.nil? || broadcast_ids.empty?
      redirect_to broadcasts_path, alert: "Nenhum broadcast selecionado!"
      return
    end

    broadcast_ids.each do |id|
      broadcast = Broadcast.find(id)
      if broadcast.uses_ffmpeg_streaming?
        command = broadcast.generate_command(broadcast.show_widgets)
        pid = spawn_broadcast_process(command)
        Process.detach(pid)
        broadcast.update(command: command, process_pid: pid, status: "running")
      else
        broadcast.update(process_pid: nil, status: "running")
      end
      notify_broadcast_event!(
        broadcast,
        event_key: "production_started",
        title: "TV em producao",
        message: "#{broadcast.name} foi iniciada pelo painel.",
        severity: "success"
      )
    end

    direct_playback_count = Broadcast.where(id: broadcast_ids).count { |broadcast| !broadcast.uses_ffmpeg_streaming? }
    notice =
      if direct_playback_count.positive?
        "Broadcasts iniciados. #{direct_playback_count} usando video direto no app oficial, sem FFmpeg."
      else
        "Commands executed successfully."
      end

    redirect_to broadcasts_path, notice: notice
  rescue Errno::ENOENT => e
    redirect_to broadcasts_path, alert: "Nao foi possivel executar os broadcasts. Verifique o FFmpeg. Detalhe: #{e.message}"
  end

  def publish_video
    broadcast_ids = Array(params[:broadcast_ids]).reject(&:blank?)
    if broadcast_ids.empty?
      redirect_to broadcasts_path, alert: "Selecione pelo menos uma TV para receber a playlist."
      return
    end

    broadcasts = Broadcast.where(id: broadcast_ids)
    if broadcasts.none?
      redirect_to broadcasts_path, alert: "Selecione pelo menos uma TV válida para receber a playlist."
      return
    end

    playlist = SavedBroadcastPlaylist.includes(items: :media_blob).find_by(id: params[:playlist_id])
    if playlist.blank?
      redirect_to broadcasts_path, alert: "Selecione uma playlist antes de publicar."
      return
    end

    blobs = playlist.items.map(&:media_blob)
    if blobs.blank?
      redirect_to broadcasts_path, alert: "Selecione uma playlist com midias ou crie uma nova."
      return
    end

    updated = []
    restarted = []

    broadcasts.find_each do |broadcast|
      publish_playlist_to_broadcast(broadcast, blobs, playlist: playlist)
      if broadcast.video.attached?
        broadcast.update!(command: broadcast.generate_command(broadcast.show_widgets))
      else
        broadcast.update!(command: nil)
      end
      restarted << broadcast.name if restart_broadcast_after_update(broadcast).present?
      notify_broadcast_event!(
        broadcast,
        event_key: "playlist_published",
        title: "Playlist publicada",
        message: "#{broadcast.name} recebeu uma nova playlist.",
        severity: "success"
      )
      updated << broadcast.name
    end

    message = "Playlist publicada em #{updated.size} TV(s)."
    message += " Reiniciadas: #{restarted.join(', ')}." if restarted.any?
    redirect_to broadcasts_path, notice: message
  rescue ActiveRecord::RecordInvalid => e
    redirect_to broadcasts_path, alert: "Nao foi possivel publicar a playlist: #{e.record.errors.full_messages.to_sentence}"
  rescue StandardError => e
    redirect_to broadcasts_path, alert: "Nao foi possivel publicar a playlist: #{e.message}"
  end

  def export_m3u
    broadcasts = Broadcast.all

    if broadcasts.empty?
      redirect_to broadcasts_path, alert: "Nenhum broadcast disponivel para exportar!"
      return
    end

    streaming_configuration = StreamingConfiguration.find_by(id: 1)
    server_host = streaming_configuration&.server_ip.presence || request.host

    m3u_entries = broadcasts.filter_map do |broadcast|
      stream_url = broadcast.stream_url.presence
      next if stream_url.blank?

      updated_stream_url = stream_url.gsub("localhost", server_host)

      "#EXTINF:-1,#{broadcast.name}\n#{updated_stream_url}"
    end

    if m3u_entries.empty?
      redirect_to broadcasts_path, alert: "Nenhum broadcast possui stream configurado para exportar!"
      return
    end

    m3u_content = m3u_entries.join("\n")

    send_data m3u_content, type: "audio/x-mpegurl", disposition: "attachment", filename: "broadcasts.m3u"
  end

  def check_tv_status
    probe = Broadcast.new(
      tv_ip: params[:tv_ip].to_s.strip,
      adb_host: params[:adb_host].to_s.strip,
      tv_device_type: params[:tv_device_type].presence || "normal_tv",
      adb_port: params[:adb_port].presence || 5555
    )

    render json: {
      online: TvDeviceService.new(probe).online?,
      control_mode: probe.control_method_summary
    }
  end

  def mobile_index
    response.headers["Cache-Control"] = "no-store"
    streaming_configuration = StreamingConfiguration.find_by(id: 1)
    mobile_device_context = resolve_mobile_device_context
    mark_mobile_app_presence!(mobile_device_context[:broadcast_id])
    notify_stale_mobile_players!
    channels = Broadcast.order(:name).map do |broadcast|
      tv_power_status = broadcast.mobile_tv_power_status
      notify_tv_power_status_change!(broadcast, tv_power_status)

      {
        id: broadcast.id,
        name: broadcast.name,
        stream_url: broadcast.ffmpeg_stream_url(streaming_configuration),
        playback_url: broadcast.app_playback_url(streaming_configuration),
        playback_mode: broadcast.preview_playback_mode_label,
        playlist_items: broadcast.mobile_playlist_payload(streaming_configuration),
        playlist_sync: mobile_playlist_sync_payload(broadcast),
        presentation_control: presentation_control_payload(broadcast),
        playback_app_type: broadcast.playback_app_type,
        official_app_browser_rotation: broadcast.playback_app_type_official_app? ? broadcast.official_app_payload(streaming_configuration, include_login: web_login_device_authorized?(broadcast)) : nil,
        official_app_widget_bar: mobile_widget_bar_payload_for(broadcast, streaming_configuration),
        thumbnail_url: mobile_thumbnail_url_for(broadcast, streaming_configuration),
        dashboard_preview: dashboard_preview_payload_for(broadcast),
        tv_ip: broadcast.tv_ip,
        keep_app_foreground_enabled: broadcast.keep_app_foreground_enabled?,
        power_schedule: mobile_power_schedule_payload(broadcast),
        device_match: mobile_device_context[:broadcast_id] == broadcast.id,
        status: broadcast.status.presence || "stopped",
        production_status: broadcast.effective_production_status,
        tv_power_status: tv_power_status,
        app_player_presence_status: broadcast.effective_app_player_presence_status,
        app_player_presence_updated_at: broadcast.app_player_presence_updated_at&.iso8601,
        config_version: mobile_config_version_for(broadcast, streaming_configuration),
        orientation: normalized_mobile_orientation(broadcast)
      }
    end

    render json: channels
  end

  def mobile_status
    response.headers["Cache-Control"] = "no-store"
    streaming_configuration = StreamingConfiguration.find_by(id: 1)
    mobile_device_context = resolve_mobile_device_context
    if mobile_device_context[:broadcast_id] == @broadcast.id
      mark_mobile_app_presence!(@broadcast.id)
      @broadcast.reload
    end
    render json: {
      id: @broadcast.id,
      status: @broadcast.status.presence || "stopped",
      production_status: @broadcast.effective_production_status,
      stream_url: @broadcast.ffmpeg_stream_url(streaming_configuration),
      playback_url: @broadcast.app_playback_url(streaming_configuration),
      playback_mode: @broadcast.preview_playback_mode_label,
      playlist_items: @broadcast.mobile_playlist_payload(streaming_configuration),
      playlist_sync: mobile_playlist_sync_payload(@broadcast),
      presentation_control: presentation_control_payload(@broadcast),
      official_app_browser_rotation: @broadcast.playback_app_type_official_app? ? @broadcast.official_app_payload(streaming_configuration, include_login: web_login_device_authorized?(@broadcast)) : nil,
      official_app_widget_bar: mobile_widget_bar_payload_for(@broadcast, streaming_configuration),
      power_schedule: mobile_power_schedule_payload(@broadcast),
      keep_app_foreground_enabled: @broadcast.keep_app_foreground_enabled?,
      device_match: mobile_device_context[:broadcast_id] == @broadcast.id,
      tv_power_status: @broadcast.mobile_tv_power_status,
      app_player_presence_status: @broadcast.effective_app_player_presence_status,
      config_version: mobile_config_version_for(@broadcast, streaming_configuration),
      orientation: normalized_mobile_orientation(@broadcast)
    }
  end

  # Lightweight endpoint for real-time presentation commands. Keeping this
  # separate avoids rebuilding and transferring the full playlist many times
  # per second for every connected TV.
  def presentation_status
    render json: { presentation_control: presentation_control_payload(@broadcast) }
  end

  def mobile_presence
    mobile_device_context = resolve_mobile_device_context

    unless mobile_device_context[:broadcast_id] == @broadcast.id
      render json: { ok: false, error: "device_mismatch" }, status: :forbidden
      return
    end

    previous_status = @broadcast.effective_app_player_presence_status
    presence_status = params[:player_status].to_s.strip
    unless @broadcast.update_mobile_presence!(
      presence_status: presence_status,
      seen_at: Time.zone.now,
      screen_on: params[:screen_on],
      playlist_item_id: params[:playlist_item_id],
      position_ms: params[:position_ms]
    )
      render json: { ok: false, error: "invalid_presence_status" }, status: :unprocessable_entity
      return
    end
    notify_mobile_player_status_change!(@broadcast.reload, previous_status, presence_status, params[:message])

    render json: {
      ok: true,
      id: @broadcast.id,
      app_player_presence_status: @broadcast.app_player_presence_status,
      app_player_presence_updated_at: @broadcast.app_player_presence_updated_at
    }
  end

  def toggle_presentation_mode
    enabled = !@broadcast.presentation_mode_enabled?
    @broadcast.update!(
      presentation_mode_enabled: enabled,
      presentation_command: nil,
      presentation_paused: false,
      presentation_command_version: @broadcast.presentation_command_version.to_i + 1,
      presentation_command_updated_at: Time.current
    )

    respond_to do |format|
      format.html { redirect_back fallback_location: broadcasts_path, notice: "Modo apresentação #{enabled ? 'ativado' : 'desativado'} para #{@broadcast.name}." }
      format.json { render json: { ok: true, presentation_control: presentation_control_payload(@broadcast) } }
    end
  end

  def toggle_forecast_widget
    enabled = !@broadcast.widget_forecast_enabled?
    @broadcast.update!(widget_forecast_enabled: enabled)

    respond_to do |format|
      format.html { redirect_back fallback_location: broadcasts_path, notice: "Previsao #{enabled ? 'ativada' : 'desativada'} para #{@broadcast.name}." }
      format.json { render json: { ok: true, enabled: enabled } }
    end
  end

  def presentation_command
    unless @broadcast.presentation_mode_enabled?
      render json: { ok: false, error: "presentation_mode_disabled" }, status: :unprocessable_entity
      return
    end

    command = params[:command].to_s
    valid_command = %w[play pause next previous pointer_hide].include?(command) ||
      command.match?(/\Ashow:\d+\z/) ||
      command.match?(/\Apointer:(?:0(?:\.\d+)?|1(?:\.0+)?):(?:0(?:\.\d+)?|1(?:\.0+)?)\z/)
    unless valid_command
      render json: { ok: false, error: "invalid_presentation_command" }, status: :unprocessable_entity
      return
    end

    @broadcast.with_lock do
      # Remote presentation commands are transient player state, not configuration.
      # Avoid touching updated_at so the Android app does not restart playback or
      # replay the playlist-update notification for every button press.
      @broadcast.update_columns(
        presentation_command: command,
        presentation_command_version: @broadcast.presentation_command_version.to_i + 1,
        presentation_command_updated_at: Time.current,
        presentation_paused: command == "pause" ? true : (command == "play" ? false : @broadcast.presentation_paused?)
      )
    end

    render json: { ok: true, presentation_control: presentation_control_payload(@broadcast) }
  end

  def mobile_player_status
    previous_status = @broadcast.effective_app_player_presence_status
    player_status = params[:player_status].to_s.strip
    unless @broadcast.update_mobile_presence!(
      presence_status: player_status,
      seen_at: Time.zone.now,
      screen_on: params[:screen_on],
      playlist_item_id: params[:playlist_item_id],
      position_ms: params[:position_ms]
    )
      render json: { ok: false, error: "invalid_player_status" }, status: :unprocessable_entity
      return
    end
    version_code = Integer(params[:app_version_code], exception: false)
    if version_code&.positive?
      @broadcast.update_columns(
        app_version_name: params[:app_version_name].to_s.strip.first(40).presence,
        app_version_code: version_code,
        app_version_reported_at: Time.current
      )
    end
    notify_mobile_player_status_change!(@broadcast.reload, previous_status, player_status, params[:message])
    analyze_playlist_sync_for!(@broadcast) if player_status == "playing"

    render json: {
      ok: true,
      id: @broadcast.id,
      app_player_presence_status: @broadcast.app_player_presence_status,
      current_player_playlist_item_id: @broadcast.current_player_playlist_item_id,
      current_player_position_ms: @broadcast.current_player_position_ms
    }
  end

  def mobile_thumbnail
    playlist_item = mobile_thumbnail_playlist_item_for(@broadcast)
    if playlist_item&.media&.attached?
      return send_mobile_playlist_item_thumbnail(playlist_item)
    end

    thumbnail_path = thumbnail_path_for(@broadcast)
    return head :not_found unless generate_thumbnail_for(@broadcast, thumbnail_path)

    send_file thumbnail_path, type: "image/jpeg", disposition: "inline"
  end

  def mobile_video
    unless @broadcast.video.attached?
      head :not_found
      return
    end

    video_path = ActiveStorage::Blob.service.path_for(@broadcast.video.blob.key)
    send_file video_path,
              filename: @broadcast.video.filename.to_s,
              type: @broadcast.video.content_type || "video/mp4",
              disposition: "inline"
  end

  def mobile_prepared_video
    unless @broadcast.video.attached?
      head :not_found
      return
    end

    prepared_path = prepared_mobile_video_path(@broadcast)
    return head :service_unavailable unless generate_prepared_mobile_video_for(@broadcast, prepared_path)

    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"

    return if send_mobile_file_range(prepared_path, filename: File.basename(prepared_path), content_type: "video/mp4")

    send_file prepared_path, filename: File.basename(prepared_path), type: "video/mp4", disposition: "inline"
  end

  def mobile_playlist_item
    item = @broadcast.playlist_items.find_by(id: params[:item_id])
    return head :not_found if item.blank? || !item.media.attached?

    media_path = ActiveStorage::Blob.service.path_for(item.media.blob.key)
    return if send_mobile_file_range(media_path, filename: item.media.filename.to_s, content_type: item.media.content_type)

    send_file media_path, filename: item.media.filename.to_s, type: item.media.content_type, disposition: "inline"
  end

  def send_mobile_file_range(path, filename:, content_type:)
    range_header = request.headers["Range"].presence || request.env["HTTP_RANGE"]
    range_header = range_header.to_s
    return false if range_header.blank?

    file_size = File.size(path)
    match = range_header.match(/\Abytes=(\d*)-(\d*)\z/)
    return head(:range_not_satisfiable) || true unless match

    start_byte = match[1].present? ? match[1].to_i : nil
    end_byte = match[2].present? ? match[2].to_i : nil
    if start_byte.nil?
      suffix_length = [end_byte.to_i, file_size].min
      start_byte = file_size - suffix_length
      end_byte = file_size - 1
    else
      end_byte = [end_byte || (file_size - 1), file_size - 1].min
    end
    return head(:range_not_satisfiable) || true if start_byte.negative? || start_byte >= file_size || end_byte < start_byte

    length = end_byte - start_byte + 1
    response.status = :partial_content
    response.headers["Accept-Ranges"] = "bytes"
    response.headers["Content-Range"] = "bytes #{start_byte}-#{end_byte}/#{file_size}"
    response.headers["Content-Length"] = length.to_s
    response.headers["Content-Type"] = content_type.presence || "application/octet-stream"
    response.headers["Content-Disposition"] = ActionDispatch::Http::ContentDisposition.format(
      disposition: "inline", filename: filename
    )
    self.response_body = Enumerator.new do |output|
      File.open(path, "rb") do |file|
        file.seek(start_byte)
        remaining = length
        while remaining.positive?
          chunk = file.read([remaining, 256.kilobytes].min)
          break if chunk.blank?

          output << chunk
          remaining -= chunk.bytesize
        end
      end
    end
    true
  end

  def power_on_tv
    result = TvDeviceService.new(@broadcast).power_on
    @broadcast.register_tv_power_action!(
      action: "power_on",
      status: result.success? ? "success" : "failed",
      message: result.message
    )
    notify_broadcast_event!(
      @broadcast,
      event_key: result.success? ? "tv_power_on_sent" : "tv_power_on_failed",
      title: result.success? ? "Comando enviado para ligar TV" : "Falha ao ligar TV",
      message: "#{@broadcast.name}: #{result.message}",
      severity: result.success? ? "info" : "error"
    )
    respond_to do |format|
      format.html do
        redirect_back fallback_location: broadcasts_path,
                      notice: (result.success? ? result.message : nil),
                      alert: (result.success? ? nil : result.message)
      end
      format.json do
        render json: { ok: result.success?, message: result.message, power_state: "on" },
               status: (result.success? ? :ok : :unprocessable_entity)
      end
    end
  end

  def power_off_tv
    result = TvDeviceService.new(@broadcast).power_off
    @broadcast.register_tv_power_action!(
      action: "power_off",
      status: result.success? ? "success" : "failed",
      message: result.message
    )
    notify_broadcast_event!(
      @broadcast,
      event_key: result.success? ? "tv_power_off_sent" : "tv_power_off_failed",
      title: result.success? ? "Comando enviado para desligar TV" : "Falha ao desligar TV",
      message: "#{@broadcast.name}: #{result.message}",
      severity: result.success? ? "info" : "error"
    )
    respond_to do |format|
      format.html do
        redirect_back fallback_location: broadcasts_path,
                      notice: (result.success? ? result.message : nil),
                      alert: (result.success? ? nil : result.message)
      end
      format.json do
        render json: { ok: result.success?, message: result.message, power_state: "off" },
               status: (result.success? ? :ok : :unprocessable_entity)
      end
    end
  end

  def volume_up_tv
    control_tv_volume(:volume_up)
  end

  def volume_down_tv
    control_tv_volume(:volume_down)
  end

  def mute_tv
    control_tv_volume(:mute)
  end

  def set_volume_tv
    result = TvDeviceService.new(@broadcast).set_volume_percent(params[:volume_percent])

    respond_to do |format|
      format.html do
        redirect_back fallback_location: broadcasts_path,
                      notice: (result.success? ? result.message : nil),
                      alert: (result.success? ? nil : result.message)
      end
      format.json do
        render json: {
          ok: result.success?,
          message: result.message,
          volume_percent: @broadcast.reload.tv_volume_percent
        }, status: (result.success? ? :ok : :unprocessable_entity)
      end
    end
  end

  def update_power_schedule
    unless current_user&.admin?
      redirect_to broadcasts_path(locale: params[:locale]), alert: "Apenas administradores podem alterar o agendamento de energia."
      return
    end

    @broadcast.update!(power_schedule_params)

    notify_broadcast_event!(
      @broadcast,
      event_key: "tv_power_schedule_updated",
      title: "Agendamento de energia atualizado",
      message: "#{@broadcast.name}: #{@broadcast.tv_power_schedule_summary}",
      severity: "info"
    )

    redirect_to broadcasts_path(locale: params[:locale]), notice: "Agendamento de energia atualizado para #{@broadcast.name}."
  rescue ActiveRecord::RecordInvalid => e
    redirect_to broadcasts_path(locale: params[:locale]), alert: e.record.errors.full_messages.to_sentence
  end

  def update_power_schedule_all
    unless current_user&.admin?
      redirect_to broadcasts_path(locale: params[:locale]), alert: "Apenas administradores podem alterar o agendamento de energia."
      return
    end

    attrs = power_schedule_params
    Broadcast.find_each do |broadcast|
      broadcast.update!(attrs)
      notify_broadcast_event!(
        broadcast,
        event_key: "tv_power_schedule_updated",
        title: "Agendamento de energia atualizado",
        message: "#{broadcast.name}: #{broadcast.tv_power_schedule_summary}",
        severity: "info"
      )
    end

    redirect_to broadcasts_path(locale: params[:locale]), notice: "Agendamento de energia aplicado a todas as TVs."
  rescue ActiveRecord::RecordInvalid => e
    redirect_to broadcasts_path(locale: params[:locale]), alert: e.record.errors.full_messages.to_sentence
  end

  def request_adb_authorization
    result = TvDeviceService.new(@broadcast).request_adb_authorization

    respond_to do |format|
      format.json do
        render json: {
          success: result.success?,
          message: result.message
        }, status: result.success? ? :ok : :unprocessable_entity
      end
      format.html do
        redirect_back fallback_location: broadcasts_path,
                      notice: (result.success? ? result.message : nil),
                      alert: (result.success? ? nil : result.message)
      end
    end
  end

  def set_android_launcher
    unless root_user?
      redirect_to edit_broadcast_path(@broadcast), alert: "Apenas o usuario root pode configurar o launcher da TV."
      return
    end

    result = TvDeviceService.new(@broadcast).set_official_app_as_launcher

    redirect_to edit_broadcast_path(@broadcast),
                notice: (result.success? ? result.message : nil),
                alert: (result.success? ? nil : result.message)
  end

  def remove_android_launcher
    unless root_user?
      redirect_to edit_broadcast_path(@broadcast), alert: "Apenas o usuario root pode remover o launcher da TV."
      return
    end

    result = TvDeviceService.new(@broadcast).remove_official_app_as_launcher

    redirect_to edit_broadcast_path(@broadcast),
                notice: (result.success? ? result.message : nil),
                alert: (result.success? ? nil : result.message)
  end

  def open_official_app
    unless current_user&.admin?
      redirect_to edit_broadcast_path(@broadcast), alert: "Apenas administradores podem abrir o app na TV."
      return
    end

    result = TvDeviceService.new(@broadcast).open_official_app

    redirect_back fallback_location: edit_broadcast_path(@broadcast),
                  notice: (result.success? ? result.message : nil),
                  alert: (result.success? ? nil : result.message)
  end

  def update_official_app
    unless current_user&.admin?
      redirect_back fallback_location: broadcasts_path, alert: "Apenas administradores podem atualizar o app da TV."
      return
    end

    apk_path = Rails.root.join("android_player_app", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    result = TvDeviceService.new(@broadcast).install_official_app(apk_path)

    redirect_back fallback_location: broadcasts_path,
                  notice: (result.success? ? result.message : nil),
                  alert: (result.success? ? nil : result.message)
  end

  def preview_stream
    filename = [params[:filename].presence, params[:format].presence].compact.join(".")
    filename = File.basename(filename.to_s)
    hls_path = File.expand_path(File.join("C:/nginx/temp/hls", filename))
    hls_root = File.expand_path("C:/nginx/temp/hls")

    unless hls_path.start_with?(hls_root) && File.exist?(hls_path)
      head :not_found
      return
    end

    case File.extname(filename)
    when ".m3u8"
      response.headers["Cache-Control"] = "no-cache"
      render plain: File.read(hls_path), content_type: "application/vnd.apple.mpegurl"
    when ".ts"
      send_file hls_path, type: "video/mp2t", disposition: "inline"
    else
      head :unsupported_media_type
    end
  end

  private

  def format_schedule_date(value)
    return nil if value.blank?
    return value.strftime("%Y-%m-%d") if value.respond_to?(:strftime)

    Date.parse(value.to_s).strftime("%Y-%m-%d")
  rescue ArgumentError, TypeError
    nil
  end

  def stop_broadcast_process(broadcast)
    return unless broadcast.process_pid.present? && broadcast.process_pid.to_i > 0

    if Gem.win_platform?
      system("taskkill /PID #{broadcast.process_pid.to_i} /F")
    else
      Process.kill("TERM", broadcast.process_pid.to_i)
      sleep(2)
      Process.kill("KILL", broadcast.process_pid.to_i) if process_alive?(broadcast.process_pid.to_i)
    end
  rescue Errno::ESRCH
    nil
  end

  def control_tv_volume(action)
    result = TvDeviceService.new(@broadcast).public_send(action)

    redirect_back fallback_location: broadcasts_path,
                  notice: (result.success? ? result.message : nil),
                  alert: (result.success? ? nil : result.message)
  end

  def restart_broadcast_after_update(broadcast)
    stop_broadcast_process(broadcast)

    if broadcast.playlist_enabled? && !broadcast.video.attached?
      broadcast.update(process_pid: nil, status: "running")
      "App atualizado automaticamente para playlist."
    elsif broadcast.uses_ffmpeg_streaming?
      command = broadcast.generate_command(broadcast.show_widgets)
      pid = spawn_broadcast_process(command)
      Process.detach(pid)
      broadcast.update(command: command, process_pid: pid, status: "running")
      "Transmissao reiniciada automaticamente."
    else
      broadcast.update(process_pid: nil, status: "running")
      "App atualizado automaticamente para video direto."
    end
  end

  def update_playback_after_save?
    params[:update_playback_after_save].to_s == "1"
  end

  def normalized_mobile_orientation(broadcast)
    broadcast.orientation.to_s.in?(%w[portrait portrait_inverted landscape]) ? broadcast.orientation.to_s : "portrait"
  end

  def mobile_config_version_for(broadcast, streaming_configuration)
    [
      broadcast.updated_at&.iso8601,
      broadcast.mobile_playlist_version,
      normalized_mobile_orientation(broadcast),
      broadcast.show_widgets?,
      broadcast.widget_bar_edge_spacing_enabled?,
      broadcast.widget_forecast_enabled?,
      broadcast.keep_app_foreground_enabled?,
      streaming_configuration&.updated_at&.iso8601
    ].compact.join("|")
  end

  def mobile_widget_bar_payload_for(broadcast, streaming_configuration)
    base_url = request.base_url if respond_to?(:request) && request.present?
    payload = streaming_configuration&.official_app_widget_bar_payload(base_url) || { enabled: false }
    payload = payload.merge(enabled: false) unless broadcast.show_widgets?
    payload = payload.merge(forecast_enabled: false) unless broadcast.widget_forecast_enabled?
    return payload if broadcast.widget_bar_edge_spacing_enabled?

    payload.merge(edge_spacing: 0)
  end

  def process_alive?(pid)
    Process.getpgid(pid)
    true
  rescue Errno::ESRCH
    false
  end

  def spawn_broadcast_process(command)
    ensure_hls_server_started
    resolved_command = command_with_ffmpeg_path(command)

    if Gem.win_platform?
      executable, *arguments = Shellwords.split(resolved_command)
      Process.spawn(executable, *arguments, out: "NUL", err: "NUL")
    else
      Process.spawn(resolved_command, out: File::NULL, err: File::NULL)
    end
  end

  def ensure_hls_server_started
    return true if hls_server_running?

    nginx_path = nginx_executable
    return false if nginx_path.blank?

    Process.spawn(nginx_path, chdir: File.dirname(nginx_path), out: Gem.win_platform? ? "NUL" : File::NULL, err: Gem.win_platform? ? "NUL" : File::NULL)
    sleep 1
    hls_server_running?
  end

  def hls_server_running?
    TCPSocket.open("127.0.0.1", 8080) { true }
  rescue Errno::ECONNREFUSED, Errno::EHOSTUNREACH, SocketError
    false
  end

  def nginx_executable
    @nginx_executable ||= NGINX_CANDIDATE_PATHS.find { |path| File.exist?(File.expand_path(path)) }
  end

  def command_with_ffmpeg_path(command)
    ffmpeg_path = ffmpeg_executable
    return command if ffmpeg_path.blank?

    command.sub(/\Affmpeg\b/, "\"#{ffmpeg_path}\"")
  end

  def ffmpeg_executable
    @ffmpeg_executable ||= FFMPEG_CANDIDATE_PATHS.find do |path|
      if path.include?("/") || path.include?("\\")
        File.exist?(File.expand_path(path))
      else
        system("where", path, out: File::NULL, err: File::NULL)
      end
    end
  end

  def generate_thumbnail_for(broadcast, thumbnail_path)
    source_input = thumbnail_source_path_for(broadcast)
    generate_thumbnail_from_source(source_input, thumbnail_path)
  end

  def generate_thumbnail_from_source(source_input, thumbnail_path)
    return false if source_input.blank? || !File.exist?(source_input)

    FileUtils.mkdir_p(File.dirname(thumbnail_path))

    if File.exist?(thumbnail_path)
      thumb_mtime = File.mtime(thumbnail_path)
      source_mtime = File.mtime(source_input)
      return true if thumb_mtime >= source_mtime && thumb_mtime >= 30.seconds.ago
    end

    ffmpeg_path = ffmpeg_executable
    return false if ffmpeg_path.blank?

    system(
      ffmpeg_path,
      "-y",
      "-ss", "00:00:01",
      "-i", source_input,
      "-vf", "thumbnail,scale=640:-1",
      "-frames:v", "1",
      thumbnail_path.to_s,
      out: File::NULL,
      err: File::NULL
    )
    File.exist?(thumbnail_path)
  end

  def thumbnail_path_for(broadcast)
    File.expand_path(File.join("tmp", "mobile_thumbnails", "broadcast_#{broadcast.id}.jpg"), Rails.root)
  end

  def playlist_item_thumbnail_path_for(item)
    File.expand_path(File.join("tmp", "mobile_thumbnails", "playlist_item_#{item.id}.jpg"), Rails.root)
  end

  def mobile_thumbnail_playlist_item_for(broadcast)
    broadcast.current_player_playlist_item ||
      (broadcast.playlist_enabled? ? broadcast.playlist_items.includes(media_attachment: :blob).first : nil)
  end

  def send_mobile_playlist_item_thumbnail(item)
    blob = item.media.blob
    media_path = ActiveStorage::Blob.service.path_for(blob.key)
    return head :not_found unless File.exist?(media_path)

    if blob.content_type.to_s.start_with?("image/")
      send_file media_path,
                filename: blob.filename.to_s,
                type: blob.content_type,
                disposition: "inline"
      return
    end

    thumbnail_path = playlist_item_thumbnail_path_for(item)
    return head :not_found unless generate_thumbnail_from_source(media_path, thumbnail_path)

    send_file thumbnail_path, type: "image/jpeg", disposition: "inline"
  rescue StandardError => error
    Rails.logger.warn("Mobile playlist thumbnail failed for item #{item&.id}: #{error.class} - #{error.message}")
    head :not_found
  end

  def thumbnail_source_path_for(broadcast)
    stream_filename = broadcast.current_stream_filename
    if stream_filename.present?
      hls_input = File.expand_path(File.join("C:/nginx/temp/hls", stream_filename))
      return hls_input if File.exist?(hls_input)
    end

    return unless broadcast.video.attached?

    ActiveStorage::Blob.service.path_for(broadcast.video.blob.key)
  rescue StandardError
    nil
  end

  def mobile_thumbnail_url_for(broadcast, streaming_configuration)
    options = {}
    if streaming_configuration&.server_ip.present?
      options[:host] = streaming_configuration.server_ip
      options[:port] = request.port
      options[:protocol] = request.protocol
    end
    options[:v] = mobile_thumbnail_version_for(broadcast)

    mobile_thumbnail_broadcast_url(broadcast, options)
  end

  def mobile_thumbnail_version_for(broadcast)
    playlist_item = mobile_thumbnail_playlist_item_for(broadcast)
    return "playlist-#{playlist_item.id}-#{playlist_item.updated_at.to_i}" if playlist_item.present?

    broadcast.cache_key_with_version
  end

  def dashboard_preview_payload_for(broadcast)
    playlist_items = broadcast.playlist_items.ordered.to_a
    current_item = broadcast.current_player_playlist_item ||
                   (broadcast.playlist_enabled? ? playlist_items.first : nil)
    current_slide_index = current_item.present? ? playlist_items.index { |item| item.id == current_item.id } : nil
    orientation = broadcast.orientation.presence || "landscape"

    if broadcast.official_app_browser_rotation_enabled? && broadcast.official_app_web_only?
      return {
        key: "web-only-#{broadcast.id}-#{broadcast.updated_at.to_i}",
        name: broadcast.name,
        mode: broadcast.preview_playback_mode_label,
        orientation: orientation,
        slide_count: 0,
        slide_index: 0,
        thumb_url: nil,
        source_url: broadcast.official_app_page_url,
        source_type: "text/html"
      }
    end

    if current_item&.media&.attached?
      blob = current_item.media.blob
      return {
        key: "playlist-item-#{current_item.id}-#{current_item.updated_at.to_i}",
        name: broadcast.name,
        mode: broadcast.preview_playback_mode_label,
        orientation: orientation,
        slide_count: playlist_items.length,
        slide_index: current_slide_index || 0,
        thumb_url: media_library_thumbnail_path(blob, v: blob.created_at.to_i),
        source_url: mobile_playlist_item_broadcast_path(broadcast, current_item, v: current_item.updated_at.to_i),
        source_type: current_item.media.content_type
      }
    end

    if broadcast.official_app_direct_video_playback_enabled?
      return {
        key: "direct-video-#{broadcast.id}-#{broadcast.cache_key_with_version}",
        name: broadcast.name,
        mode: broadcast.preview_playback_mode_label,
        orientation: orientation,
        slide_count: 1,
        slide_index: 0,
        thumb_url: mobile_thumbnail_broadcast_path(broadcast, v: broadcast.cache_key_with_version),
        source_url: mobile_prepared_video_broadcast_path(broadcast, v: broadcast.cache_key_with_version),
        source_type: "video/mp4"
      }
    end

    if broadcast.current_stream_filename.present?
      return {
        key: "stream-#{broadcast.id}-#{broadcast.current_stream_filename}",
        name: broadcast.name,
        mode: broadcast.preview_playback_mode_label,
        orientation: orientation,
        thumb_url: mobile_thumbnail_broadcast_path(broadcast, v: broadcast.cache_key_with_version),
        source_url: preview_stream_broadcast_path(broadcast, filename: broadcast.current_stream_filename),
        source_type: "application/x-mpegURL"
      }
    end

    {
      key: "empty-#{broadcast.id}-#{broadcast.updated_at.to_i}",
      name: broadcast.name,
      mode: broadcast.preview_playback_mode_label,
      orientation: orientation,
      thumb_url: mobile_thumbnail_broadcast_path(broadcast, v: broadcast.cache_key_with_version),
      source_url: nil,
      source_type: "image/jpeg"
    }
  end

  def prepared_mobile_video_path(broadcast)
    version = (((broadcast.updated_at || Time.zone.now).to_f) * 1_000_000).to_i
    filename = "broadcast_#{broadcast.id}_#{broadcast.orientation.presence || 'default'}_#{version}.mp4"
    File.expand_path(File.join("tmp", "mobile_prepared_videos", filename), Rails.root)
  end

  def generate_prepared_mobile_video_for(broadcast, prepared_path)
    source_path = ActiveStorage::Blob.service.path_for(broadcast.video.blob.key)
    return false unless File.exist?(source_path)

    FileUtils.mkdir_p(File.dirname(prepared_path))

    if File.exist?(prepared_path)
      prepared_mtime = File.mtime(prepared_path)
      source_mtime = File.mtime(source_path)
      broadcast_mtime = broadcast.updated_at || Time.zone.at(0)
      return true if prepared_mtime >= source_mtime && prepared_mtime >= broadcast_mtime
    end

    ffmpeg_path = ffmpeg_executable
    return false if ffmpeg_path.blank?

    video_filter =
      if broadcast.orientation == "portrait_inverted"
        "scale=768:1366,setdar=9/16,transpose=2,setsar=1"
      elsif broadcast.orientation == "portrait"
        "scale=768:1366,setdar=9/16,transpose=1,setsar=1"
      else
        "scale=1366:768,setdar=16/9,setsar=1"
      end

    system(
      ffmpeg_path,
      "-y",
      "-fflags", "+genpts",
      "-i", source_path,
      "-vf", video_filter,
      "-r", "30",
      "-pix_fmt", "yuv420p",
      "-c:v", "libx264",
      "-profile:v", "baseline",
      "-level", "3.2",
      "-preset", "veryfast",
      "-tune", "fastdecode",
      "-movflags", "+faststart",
      "-c:a", "aac",
      "-ac", "2",
      "-ar", "48000",
      "-b:a", "128k",
      "-max_muxing_queue_size", "1024",
      prepared_path,
      out: File::NULL,
      err: File::NULL
    )

    File.exist?(prepared_path)
  end

  def set_broadcast
    @broadcast = Broadcast.find(params[:id])
  end

  def resolve_mobile_device_context
    device_token = request.headers["X-TV-Device-Token"].to_s.strip.presence || params[:device_token].to_s.strip.presence
    device_ips = extract_mobile_device_ips
    seen_at = Time.zone.now

    matched_broadcast = Broadcast.find_by(app_device_token: device_token) if device_token.present?
    ip_matched_broadcast =
      if device_ips.any?
        Broadcast
          .where(tv_ip: device_ips)
          .order(Arel.sql("CASE WHEN status = 'running' THEN 0 ELSE 1 END"), updated_at: :desc, id: :desc)
          .first
      end

    if ip_matched_broadcast.present? &&
        (matched_broadcast.blank? || (matched_broadcast.id != ip_matched_broadcast.id && matched_broadcast.status.to_s != "running"))
      matched_broadcast = ip_matched_broadcast
      safely_bind_app_device(matched_broadcast, device_token, seen_at) if device_token.present?
    elsif matched_broadcast.present?
      safely_bind_app_device(matched_broadcast, device_token, seen_at)
    end

    {
      token: device_token,
      ips: device_ips,
      broadcast_id: matched_broadcast&.id
    }
  end

  # Credentials are delivered only to the bound TV from its configured address.
  # Do not trust the client-supplied list of device IPs for secret delivery.
  def web_login_device_authorized?(broadcast)
    token = request.headers["X-TV-Device-Token"].to_s
    expected = broadcast.reload.app_device_token.to_s
    token.present? && expected.present? &&
      ActiveSupport::SecurityUtils.secure_compare(token, expected) &&
      request.remote_ip == broadcast.tv_ip.to_s.strip
  end

  def mark_mobile_app_presence!(broadcast_id)
    return if broadcast_id.blank?

    seen_at = Time.zone.now
    broadcast = Broadcast.find_by(id: broadcast_id)
    return if broadcast.blank?

    previous_status = broadcast.effective_app_player_presence_status
    presence_status =
      if broadcast.effective_app_player_presence_status == "playing"
        "playing"
      else
        "online"
      end

    broadcast.update_columns(
      app_player_presence_status: presence_status,
      app_player_presence_updated_at: seen_at,
      app_device_last_seen_at: seen_at
    )
    notify_mobile_player_status_change!(broadcast.reload, previous_status, presence_status)
  end

  def safely_bind_app_device(broadcast, device_token, seen_at)
    Broadcast.where(app_device_token: device_token).where.not(id: broadcast.id).update_all(
      app_device_token: nil,
      app_device_registered_at: nil,
      app_device_last_seen_at: nil
    )
    broadcast.bind_app_device!(device_token: device_token, seen_at: seen_at)
  rescue ActiveRecord::RecordNotUnique
    Broadcast.where(app_device_token: device_token).where.not(id: broadcast.id).update_all(
      app_device_token: nil,
      app_device_registered_at: nil,
      app_device_last_seen_at: nil
    )
    broadcast.bind_app_device!(device_token: device_token, seen_at: seen_at)
  end

  def extract_mobile_device_ips
    header_ips = request.headers["X-TV-Device-Ips"].to_s
    ips = header_ips.split(",").map(&:strip).reject(&:blank?)
    ips << request.remote_ip.to_s.strip if request.remote_ip.present?
    ips.select { |ip| ip.match?(/\A((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)\z/) }.uniq
  end

  def set_available_video_blobs
    @available_video_blobs = video_library_blobs
  end

  def set_playlist_library
    # The index contains a hidden playlist editor. Loading every media blob here
    # made the main TVs page pay the full library cost before the editor opened.
    @video_library_blobs = action_name == "index" ? [] : media_library_blobs
    @saved_playlists = SavedBroadcastPlaylist.order(updated_at: :desc).includes(items: :media_blob)
  end

  def video_library_blobs
    ActiveStorage::Blob
      .where("content_type LIKE ?", "video/%")
      .order(created_at: :desc)
  end

  def media_library_blobs
    ActiveStorage::Blob
      .where("content_type LIKE ? OR content_type LIKE ?", "video/%", "image/%")
      .where.not("lower(filename) LIKE ? OR lower(filename) LIKE ?", "%clicar%", "%clique%")
      .where.not(id: weather_widget_blob_ids)
      .order(created_at: :desc)
  end

  def weather_widget_blob_ids
    ActiveStorage::Attachment
      .where(record_type: "StreamingConfiguration")
      .where(name: %w[
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
      ])
      .select(:blob_id)
  end

  def published_media_blobs
    uploaded_blobs = Array(params[:publish_media_files]).filter_map do |uploaded_file|
      next if uploaded_file.blank?
      next unless supported_publish_media?(uploaded_file.content_type)

      ActiveStorage::Blob.create_and_upload!(
        io: uploaded_file.open,
        filename: uploaded_file.original_filename,
        content_type: uploaded_file.content_type
      )
    end

    selected_ids = Array(params[:selected_media_blob_ids]).reject(&:blank?)
    selected_blobs = media_library_blobs.where(id: selected_ids).to_a

    (selected_blobs + uploaded_blobs).uniq(&:id)
  end

  def supported_publish_media?(content_type)
    content_type.to_s.start_with?("video/") || content_type.to_s.start_with?("image/")
  end

  def publish_playlist_to_broadcast(broadcast, blobs, playlist: nil)
    broadcast.playlist_items.destroy_all
    sync_started_at = playlist&.updated_at || Time.current
    sync_enabled = broadcast.playlist_sync_enabled?
    broadcast.assign_attributes(
      saved_broadcast_playlist_id: playlist&.id,
      playlist_sync_started_at: sync_enabled ? sync_started_at : nil
    )

    blobs.each_with_index do |blob, index|
      saved_item = playlist&.items&.detect { |playlist_item| playlist_item.media_blob_id == blob.id }
      item = broadcast.playlist_items.build(
        position: index,
        duration_seconds: saved_item&.duration_seconds || playlist&.image_duration_seconds || publish_image_duration_seconds,
        video_duration_mode: playlist&.video_duration_mode || "image_duration",
        transition_style: saved_item&.transition_style || playlist&.transition_style || "fade",
        transition_duration_ms: saved_item&.transition_duration_ms || playlist&.transition_duration_ms || 600
      )
      item.media.attach(blob)
      item.save!
    end

    first_video_blob = blobs.find { |blob| blob.content_type.to_s.start_with?("video/") }
    if first_video_blob.present?
      broadcast.video.attach(first_video_blob)
    else
      broadcast.video.purge if broadcast.video.attached?
    end
  end

  def mobile_playlist_sync_payload(broadcast)
    expected = expected_playlist_position_for(broadcast)
    sync_started_at = effective_playlist_sync_started_at(broadcast)

    {
      enabled: automatic_playlist_sync_enabled?(broadcast) && broadcast.playlist_items.any?,
      started_at: sync_started_at&.iso8601,
      started_at_ms: sync_started_at ? (sync_started_at.to_f * 1000).to_i : nil,
      server_time_ms: (Time.current.to_f * 1000).to_i,
      playlist_id: broadcast.saved_broadcast_playlist_id,
      expected_item_id: expected&.dig(:item)&.id,
      expected_index: expected&.dig(:index),
      expected_elapsed_ms: expected&.dig(:elapsed_ms),
      resync_token: broadcast.playlist_resync_requested_at&.to_f&.*(1000)&.to_i,
      resync_reason: broadcast.playlist_resync_reason
    }
  end

  def presentation_control_payload(broadcast)
    {
      enabled: broadcast.presentation_mode_enabled?,
      paused: broadcast.presentation_paused?,
      command: broadcast.presentation_command,
      command_version: broadcast.presentation_command_version.to_i,
      command_updated_at: broadcast.presentation_command_updated_at&.iso8601,
      command_url: presentation_command_broadcast_path(broadcast),
      playlist_item_count: broadcast.playlist_items.count,
      current_item_id: broadcast.current_player_playlist_item_id
    }
  end

  def analyze_playlist_sync_for!(broadcast)
    return unless automatic_playlist_sync_enabled?(broadcast)
    return if broadcast.saved_broadcast_playlist_id.blank?
    return unless broadcast.app_player_playing?

    expected = expected_playlist_position_for(broadcast)
    return if expected.blank?

    current_item = broadcast.current_player_playlist_item
    return if current_item.blank?

    current_position_ms = broadcast.current_player_position_ms.to_i
    item_mismatch = current_item.id != expected[:item].id
    drift_ms = (current_position_ms - expected[:elapsed_ms].to_i).abs
    drift_ms = 0 if item_mismatch
    return unless item_mismatch || drift_ms > PLAYLIST_SYNC_DRIFT_THRESHOLD_MS
    return if broadcast.playlist_resync_requested_at.present? &&
              broadcast.playlist_resync_requested_at > PLAYLIST_SYNC_RESYNC_COOLDOWN.ago

    reason = item_mismatch ? "item_fora_da_sincronia" : "atraso_#{drift_ms}ms"
    broadcast.update_columns(
      playlist_resync_requested_at: Time.current,
      playlist_resync_reason: reason
    )
  end

  def expected_playlist_position_for(broadcast)
    return unless automatic_playlist_sync_enabled?(broadcast)

    sync_started_at = effective_playlist_sync_started_at(broadcast)
    return if sync_started_at.blank?

    items = broadcast.playlist_items.ordered.to_a
    return if items.blank?

    durations = items.map { |item| item.duration_seconds.to_i.clamp(3, 3600) * 1000 }
    total_duration_ms = durations.sum
    return if total_duration_ms <= 0

    cursor_ms = (((Time.current - sync_started_at) * 1000).to_i % total_duration_ms)
    items.each_with_index do |item, index|
      duration_ms = durations[index]
      return { item: item, index: index, elapsed_ms: cursor_ms, remaining_ms: duration_ms - cursor_ms } if cursor_ms < duration_ms

      cursor_ms -= duration_ms
    end

    { item: items.first, index: 0, elapsed_ms: 0, remaining_ms: durations.first }
  end

  def automatic_playlist_sync_enabled?(broadcast)
    broadcast.playlist_sync_enabled?
  end

  def effective_playlist_sync_started_at(broadcast)
    return broadcast.playlist_sync_started_at if broadcast.playlist_sync_started_at.present?
    return unless automatic_playlist_sync_enabled?(broadcast)

    SavedBroadcastPlaylist.where(id: broadcast.saved_broadcast_playlist_id).pick(:updated_at)
  end

  def mobile_power_schedule_payload(broadcast)
    {
      enabled: broadcast.tv_power_schedule_configured?,
      on_time: broadcast.tv_power_on_time_input_value,
      off_time: broadcast.tv_power_off_time_input_value,
      disabled_weekdays: broadcast.send(:normalized_tv_disabled_weekdays),
      server_time_ms: (Time.current.to_f * 1000).to_i,
      timezone_offset_minutes: Time.zone.now.utc_offset / 60
    }
  end

  def publish_image_duration_seconds
    value = params[:playlist_image_duration_seconds].to_i
    value.positive? ? value.clamp(3, 3600) : 10
  end

  def attach_selected_video_blob(broadcast)
    selected_blob_id = params.dig(:broadcast, :selected_video_blob_id).presence
    return if selected_blob_id.blank?

    selected_blob = @available_video_blobs.find { |blob| blob.id == selected_blob_id.to_i } ||
                    ActiveStorage::Blob.find_by(id: selected_blob_id)
    return if selected_blob.blank?

    broadcast.video.attach(selected_blob)
    broadcast.playlist_items.destroy_all
  end

  def selected_broadcast_playlist
    playlist_id = params.dig(:broadcast, :selected_playlist_id).presence
    return if playlist_id.blank?

    SavedBroadcastPlaylist.includes(items: :media_blob).find_by(id: playlist_id)
  end

  def redirect_with_android_tv_configuration(success_message:)
    adb_result = configure_android_tv_defaults(@broadcast)

    if adb_result.nil? || adb_result.success?
      notice_message = [success_message, adb_result&.message].compact.join(" ")
      redirect_to broadcasts_path, notice: notice_message
    else
      redirect_to broadcasts_path, alert: "#{success_message} Porem a configuracao ADB automatica falhou: #{adb_result.message}"
    end
  end

  def configure_android_tv_defaults(broadcast)
    return nil unless broadcast.adb_device?
    return nil if broadcast.adb_target_host.blank?

    TvDeviceService.new(broadcast).configure_android_tv_defaults
  end

  def notify_broadcast_event!(broadcast, event_key:, title:, message:, severity: "info", metadata: {})
    SystemNotification.emit!(
      event_key: event_key,
      title: title,
      message: message,
      severity: severity,
      source: broadcast,
      metadata: metadata
    )
  end

  def notify_mobile_player_status_change!(broadcast, previous_status, current_status, message = nil)
    normalized_status = current_status.to_s
    return if normalized_status.blank?

    if normalized_status == "error"
      notify_broadcast_event!(
        broadcast,
        event_key: "player_error",
        title: "Erro no app da TV",
        message: "#{broadcast.name}: #{message.presence || 'o player informou uma falha.'}",
        severity: "error"
      )
      return
    end

    return if previous_status.to_s == normalized_status

    case normalized_status
    when "playing"
      notify_broadcast_event!(
        broadcast,
        event_key: "player_playing",
        title: "Reproducao iniciada",
        message: "#{broadcast.name} comecou a reproduzir pelo app.",
        severity: "success"
      )
    when "online"
      notify_broadcast_event!(
        broadcast,
        event_key: "player_online",
        title: "App aberto na TV",
        message: "#{broadcast.name} esta com o app Aponti TV aberto.",
        severity: "info"
      )
    when "offline"
      notify_broadcast_event!(
        broadcast,
        event_key: "player_offline",
        title: "App saiu da reproducao",
        message: "#{broadcast.name}: #{message.presence || 'voltou para a selecao ou fechou o player.'}",
        severity: "warning"
      )
    end
  end

  def notify_stale_mobile_players!
    Broadcast.where(app_player_presence_status: %w[online playing error])
             .where("app_player_presence_updated_at IS NULL OR app_player_presence_updated_at < ?", Broadcast::APP_PLAYER_PRESENCE_TTL.ago)
             .find_each do |broadcast|
      previous_status = broadcast.app_player_presence_status
      broadcast.update_columns(
        app_player_presence_status: "offline",
        current_player_playlist_item_id: nil,
        current_player_playlist_item_updated_at: nil
      )
      notify_broadcast_event!(
        broadcast,
        event_key: "player_timeout",
        title: "App parou de responder",
        message: "#{broadcast.name} nao enviou presenca nos ultimos #{Broadcast::APP_PLAYER_PRESENCE_TTL.to_i} segundos. Status anterior: #{previous_status}.",
        severity: "warning"
      )
    end
  end

  def notify_tv_power_status_change!(broadcast, current_status)
    normalized_status = current_status.to_s
    return if normalized_status == "unavailable"

    previous_status = broadcast.last_known_tv_power_status.to_s
    if previous_status.blank?
      broadcast.update_columns(
        last_known_tv_power_status: normalized_status,
        last_known_tv_power_status_at: Time.current
      )
      return
    end

    return if previous_status == normalized_status

    broadcast.update_columns(
      last_known_tv_power_status: normalized_status,
      last_known_tv_power_status_at: Time.current
    )

    notify_broadcast_event!(
      broadcast,
      event_key: "tv_power_#{normalized_status}",
      title: normalized_status == "on" ? "TV ligada" : "TV desligada",
      message: "#{broadcast.name} mudou de #{previous_status == 'on' ? 'ligada' : 'desligada'} para #{normalized_status == 'on' ? 'ligada' : 'desligada'}.",
      severity: normalized_status == "on" ? "success" : "warning"
    )
  end

  def root_user?
    current_user&.admin? && current_user.email.to_s == ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local")
  end

  def require_admin_for_broadcast_edit
    return if current_user&.admin?

    redirect_to broadcasts_path, alert: "Apenas administradores podem editar TVs."
  end

  def power_schedule_params
    params.require(:broadcast).permit(:tv_power_on_time, :tv_power_off_time, tv_disabled_weekdays: [])
  end

  def broadcast_params
    params.require(:broadcast).permit(
      :name,
      :video,
      :show_widgets,
      :event_date,
      :orientation,
      :playback_app_type,
      :tv_device_type,
      :tv_ip,
      :adb_host,
      :tv_mac_address,
      :adb_port,
      :official_app_page_url,
      :official_app_login_enabled,
      :official_app_login_username,
      :official_app_login_password,
      :official_app_web_enabled,
      :official_app_web_only,
      :official_app_rotation_trigger,
      :official_app_switch_interval_unit,
      :official_app_switch_interval_seconds,
      :official_app_page_duration_unit,
      :official_app_page_duration_seconds,
      :official_app_transition_style,
      :official_app_transition_duration_ms,
      :widget_bar_edge_spacing_enabled,
      :widget_forecast_enabled,
      :keep_app_foreground_enabled,
      :playlist_sync_enabled,
      :tv_power_on_time,
      :tv_power_off_time,
      tv_disabled_weekdays: []
    )
  end
end
