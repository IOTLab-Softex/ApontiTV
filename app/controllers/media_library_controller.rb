require "digest"

class MediaLibraryController < ApplicationController
  before_action :authenticate_user!
  before_action :set_blob, only: [:thumbnail, :destroy]

  def index
    @blobs = ActiveStorage::Blob
      .where("content_type LIKE ? OR content_type LIKE ?", "video/%", "image/%")
      .where.not(id: weather_widget_blob_ids)
      .order(created_at: :desc)
  end

  def create
    uploaded_files = Array(params[:media_files]).reject(&:blank?)
    if uploaded_files.blank?
      redirect_to media_library_path, alert: "Selecione pelo menos um video ou imagem."
      return
    end

    saved_count = 0
    rejected_names = []

    uploaded_files.each do |uploaded_file|
      content_type = detected_content_type(uploaded_file)
      unless supported_media?(content_type)
        rejected_names << uploaded_file.original_filename.to_s
        next
      end

      ActiveStorage::Blob.create_and_upload!(
        io: uploaded_file.open,
        filename: uploaded_file.original_filename,
        content_type: content_type
      )
      saved_count += 1
    end

    if saved_count.zero?
      redirect_to media_library_path, alert: "Nenhum arquivo foi salvo. Envie apenas videos ou imagens."
    elsif rejected_names.any?
      redirect_to media_library_path, notice: "#{saved_count} arquivo(s) salvo(s). Ignorados: #{rejected_names.join(', ')}."
    else
      redirect_to media_library_path, notice: "#{saved_count} arquivo(s) salvo(s) na Biblioteca."
    end
  rescue StandardError => e
    redirect_to media_library_path, alert: "Nao foi possivel salvar todos os arquivos: #{e.message}"
  end

  def destroy
    @blob.purge
    redirect_to media_library_path, notice: "Midia excluida."
  end

  def thumbnail
    return head :not_found unless supported_media?(@blob.content_type)

    thumbnail_path = thumbnail_path_for(@blob)
    generate_thumbnail_for(@blob, thumbnail_path)

    if File.exist?(thumbnail_path)
      expires_in 30.days, public: true
      send_file thumbnail_path, type: "image/jpeg", disposition: "inline"
    else
      send_data thumbnail_placeholder_svg(@blob),
                type: "image/svg+xml",
                disposition: "inline"
    end
  end

  private

  def set_blob
    @blob = ActiveStorage::Blob.find(params[:id])
  end

  def supported_media?(content_type)
    content_type.to_s.start_with?("video/") || content_type.to_s.start_with?("image/")
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

  def generate_thumbnail_for(blob, thumbnail_path)
    return true if fresh_thumbnail?(blob, thumbnail_path)

    source_path = ActiveStorage::Blob.service.path_for(blob.key)
    return false unless File.exist?(source_path)

    FileUtils.mkdir_p(File.dirname(thumbnail_path))
    FileUtils.rm_f(thumbnail_path)

    command = [
      ffmpeg_path,
      "-y",
      "-hide_banner",
      "-loglevel", "error"
    ]

    command.concat(["-ss", "00:00:01"]) if blob.content_type.to_s.start_with?("video/")

    command.concat([
      "-i", source_path,
      "-frames:v", "1",
      "-vf", "scale='min(480,iw)':-2",
      "-q:v", "5",
      thumbnail_path
    ])

    system(*command)
    File.exist?(thumbnail_path)
  rescue StandardError => error
    Rails.logger.warn("[MediaLibraryThumbnail] #{error.class}: #{error.message}")
    false
  end

  def fresh_thumbnail?(blob, thumbnail_path)
    File.exist?(thumbnail_path) && File.mtime(thumbnail_path) >= blob.created_at
  end

  def thumbnail_path_for(blob)
    version = Digest::SHA256.hexdigest("#{blob.checksum}-#{blob.created_at.to_i}").first(16)
    File.expand_path(File.join("tmp", "media_library_thumbnails", "#{blob.id}-#{version}.jpg"), Rails.root)
  end

  def ffmpeg_path
    ENV["FFMPEG_PATH"].presence || "C:/nginx/ffmpeg/bin/ffmpeg.exe"
  end

  def thumbnail_placeholder_svg(blob)
    icon = blob.content_type.to_s.start_with?("image/") ? "IMAGEM" : "VIDEO"
    <<~SVG
      <svg xmlns="http://www.w3.org/2000/svg" width="480" height="270" viewBox="0 0 480 270">
        <defs>
          <linearGradient id="bg" x1="0" x2="1" y1="0" y2="1">
            <stop offset="0%" stop-color="#141a31"/>
            <stop offset="100%" stop-color="#321068"/>
          </linearGradient>
        </defs>
        <rect width="480" height="270" fill="url(#bg)"/>
        <circle cx="240" cy="112" r="42" fill="#8b2ff2" opacity=".84"/>
        <text x="240" y="119" text-anchor="middle" font-family="Arial, sans-serif" font-size="16" font-weight="700" fill="#fff">#{icon}</text>
        <text x="240" y="166" text-anchor="middle" font-family="Arial, sans-serif" font-size="18" font-weight="700" fill="#f8f7ff">Preview</text>
      </svg>
    SVG
  end

  def detected_content_type(uploaded_file)
    declared_type = uploaded_file.content_type.to_s
    return declared_type if supported_media?(declared_type)

    Marcel::MimeType.for(
      uploaded_file.open,
      name: uploaded_file.original_filename,
      declared_type: declared_type.presence
    )
  end
end
