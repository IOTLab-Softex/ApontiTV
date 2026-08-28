class BroadcastPlaylistsController < ApplicationController
  skip_before_action :verify_authenticity_token
  before_action :authenticate_user!
  before_action :set_playlist, only: [:show, :update, :destroy]

  def index
    playlists = SavedBroadcastPlaylist.order(updated_at: :desc).includes(items: :media_blob)
    render json: playlists.map { |playlist| playlist.as_editor_json(view_context, preview_url_builder: method(:media_blob_preview_url)) }
  end

  def show
    render json: @playlist.as_editor_json(view_context, preview_url_builder: method(:media_blob_preview_url))
  end

  def create
    playlist = nil
    SavedBroadcastPlaylist.transaction do
      playlist = SavedBroadcastPlaylist.new(playlist_attributes)
      save_playlist_items(playlist)
      playlist.save!
    end

    render json: playlist.as_editor_json(view_context, preview_url_builder: method(:media_blob_preview_url)), status: :created
  rescue ActiveRecord::RecordInvalid => e
    render json: { error: e.record.errors.full_messages.to_sentence }, status: :unprocessable_entity
  rescue StandardError => e
    Rails.logger.error("[BroadcastPlaylistsController#create] #{e.class}: #{e.message}\n#{e.backtrace&.first(10)&.join("\n")}")
    render json: { error: "Nao foi possivel salvar a playlist: #{e.message}" }, status: :internal_server_error
  end

  def update
    SavedBroadcastPlaylist.transaction do
      @playlist.assign_attributes(playlist_attributes)
      save_playlist_items(@playlist)
      @playlist.save!
    end

    render json: @playlist.as_editor_json(view_context, preview_url_builder: method(:media_blob_preview_url))
  rescue ActiveRecord::RecordInvalid => e
    render json: { error: e.record.errors.full_messages.to_sentence }, status: :unprocessable_entity
  rescue StandardError => e
    Rails.logger.error("[BroadcastPlaylistsController#update] #{e.class}: #{e.message}\n#{e.backtrace&.first(10)&.join("\n")}")
    render json: { error: "Nao foi possivel salvar a playlist: #{e.message}" }, status: :internal_server_error
  end

  def destroy
    @playlist.destroy
    head :no_content
  end

  def upload_media
    uploaded_files = Array(params[:media_files]).reject(&:blank?)
    if uploaded_files.blank?
      render json: { error: "Selecione pelo menos um arquivo." }, status: :unprocessable_entity
      return
    end

    blobs = uploaded_files.filter_map do |uploaded_file|
      next unless supported_media?(uploaded_file.content_type)
      next if hidden_library_filename?(uploaded_file.original_filename)

      ActiveStorage::Blob.create_and_upload!(
        io: uploaded_file.open,
        filename: uploaded_file.original_filename,
        content_type: uploaded_file.content_type
      )
    end

    if blobs.blank?
      render json: { error: "Envie apenas videos ou imagens." }, status: :unprocessable_entity
      return
    end

    render json: blobs.map { |blob| media_blob_json(blob) }
  end

  def media_library
    render json: media_library_blobs.map { |blob| media_blob_json(blob) }
  end

  private

  def set_playlist
    @playlist = SavedBroadcastPlaylist.find(params[:id])
  end

  def playlist_attributes
    {
      name: params[:name].to_s.strip.presence || "Playlist sem nome",
      image_duration_seconds: integer_param(:image_duration_seconds, default: 10, min: 3, max: 3600),
      video_duration_mode: SavedBroadcastPlaylist::VIDEO_DURATION_MODES.include?(params[:video_duration_mode].to_s) ? params[:video_duration_mode] : "image_duration",
      transition_style: SavedBroadcastPlaylist::TRANSITION_STYLES.include?(params[:transition_style].to_s) ? params[:transition_style] : "fade",
      transition_duration_ms: integer_param(:transition_duration_ms, default: 600, min: 0, max: 10_000),
      sync_enabled: ActiveModel::Type::Boolean.new.cast(params.fetch(:sync_enabled, false))
    }
  end

  def save_playlist_items(playlist)
    blob_ids = Array(params[:media_blob_ids]).map(&:to_i).select(&:positive?).uniq
    blobs = media_library_blobs.where(id: blob_ids).index_by(&:id)

    playlist.items.destroy_all if playlist.persisted?
    blob_ids.each_with_index do |blob_id, index|
      blob = blobs[blob_id]
      next if blob.blank?

      playlist.items.build(
        media_blob: blob,
        position: index,
        duration_seconds: playlist.image_duration_seconds,
        transition_style: playlist.transition_style,
        transition_duration_ms: playlist.transition_duration_ms
      )
    end
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

  def hidden_library_filename?(filename)
    filename.to_s.downcase.match?(/clicar|clique/)
  end

  def supported_media?(content_type)
    content_type.to_s.start_with?("video/") || content_type.to_s.start_with?("image/")
  end

  def media_blob_json(blob)
    {
      id: blob.id,
      type: blob.content_type.to_s.start_with?("image/") ? "image" : "video",
      filename: blob.filename.to_s,
      content_type: blob.content_type,
      size_label: view_context.number_to_human_size(blob.byte_size),
      created_at_label: blob.created_at.strftime("%d/%m/%Y %H:%M"),
      preview_url: media_blob_preview_url(blob)
    }
  end

  def media_blob_preview_url(blob)
    media_library_thumbnail_path(blob, v: blob.checksum.presence || blob.created_at.to_i)
  end

  def integer_param(name, default:, min:, max:)
    value = params[name].to_i
    value = default unless value.positive? || min.zero?
    value.clamp(min, max)
  end
end
