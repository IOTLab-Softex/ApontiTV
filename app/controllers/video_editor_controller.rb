require "tempfile"

class VideoEditorController < ApplicationController
  before_action :authenticate_user!
  before_action :set_project, only: [:edit, :destroy]

  EditorUpload = Struct.new(:tempfile, :original_filename, :content_type, keyword_init: true)

  def index
    @projects = VideoEditorProject
      .where(user: current_user)
      .order(updated_at: :desc)
      .limit(50)
  end

  def new
    @restore_data = nil
  end

  def edit
    @restore_data = @project.project_data
    render :new
  end

  def create
    files = resolved_editor_files
    if files.blank?
      redirect_to new_video_editor_path, alert: "Adicione pelo menos uma foto ou video para criar o MP4."
      return
    end

    blob = VideoEditorRenderer.new(
      files: files,
      item_order: params[:item_order],
      durations: params[:durations],
      start_times: params[:start_times],
      captions: params[:captions],
      caption_layers: params[:caption_layers],
      caption_positions: params[:caption_positions],
      caption_x: params[:caption_x],
      caption_y: params[:caption_y],
      caption_sizes: params[:caption_sizes],
      caption_weights: params[:caption_weights],
      caption_colors: params[:caption_colors],
      caption_backgrounds: params[:caption_backgrounds],
      transitions: params[:transitions],
      transition_durations: params[:transition_durations],
      timeline_data: params[:timeline_data],
      project_duration: params[:project_duration],
      title: params[:title],
      orientation: params[:orientation]
    ).render!

    save_project(blob)

    redirect_to new_broadcast_path(broadcast: { selected_video_blob_id: blob.id }),
                notice: "Video criado com sucesso. Ele ja esta selecionado para o novo broadcast."
  rescue VideoEditorRenderer::RenderError => e
    redirect_to new_video_editor_path, alert: e.message
  ensure
    cleanup_resolved_files
  end

  def save_draft
    project = persist_project(nil)

    render json: {
      ok: true,
      id: project.id,
      edit_url: edit_video_editor_project_path(project),
      index_url: video_editor_path,
      media: project_media_payload(project)
    }
  rescue StandardError => e
    render json: { ok: false, error: e.message }, status: :unprocessable_entity
  end

  def destroy
    @project.destroy
    redirect_to video_editor_path, notice: "Projeto excluido."
  end

  private

  def set_project
    @project = VideoEditorProject.find_by!(id: params[:id], user: current_user)
  end

  def uploaded_media_files
    @uploaded_media_files ||= Array(params[:media_files]).reject(&:blank?)
  end

  def media_manifest_entries
    @media_manifest_entries ||= begin
      raw = params[:media_manifest].presence
      parsed = raw.present? ? JSON.parse(raw) : []
      parsed.is_a?(Array) ? parsed : []
    rescue JSON::ParserError
      []
    end
  end

  def resolved_editor_files
    entries = media_manifest_entries
    if entries.blank?
      return uploaded_media_files
    end

    @resolved_tempfiles = []
    entries.filter_map do |entry|
      if entry["blob_id"].present?
        blob_upload_for(entry["blob_id"])
      elsif entry.key?("upload_index")
        uploaded_media_files[entry["upload_index"].to_i]
      end
    end
  end

  def blob_upload_for(blob_id)
    blob = ActiveStorage::Blob.find_by(id: blob_id)
    return nil unless blob

    extension = File.extname(blob.filename.to_s)
    tempfile = Tempfile.new(["video-editor-blob", extension], binmode: true)
    blob.download { |chunk| tempfile.write(chunk) }
    tempfile.rewind
    @resolved_tempfiles << tempfile

    EditorUpload.new(
      tempfile: tempfile,
      original_filename: blob.filename.to_s,
      content_type: blob.content_type
    )
  end

  def cleanup_resolved_files
    Array(@resolved_tempfiles).each do |tempfile|
      tempfile.close
      tempfile.unlink
    rescue StandardError
      nil
    end
  end

  def save_project(blob)
    persist_project(blob)
  rescue StandardError
    nil
  end

  def persist_project(rendered_blob)
    project = current_project || VideoEditorProject.new(user: current_user)

    project.title = params[:title].presence || project.title.presence || "Projeto #{Time.zone.now.strftime('%d/%m %H:%M')}"
    project.orientation = params[:orientation].presence || project.orientation.presence || "landscape"
    project.rendered_blob_id = rendered_blob.id if rendered_blob.present?
    project.save!

    normalized_media = persist_project_media!(project)
    project.update!(project_data: project_data_payload(normalized_media).to_json)
    project
  end

  def current_project
    project_id = params[:project_id].presence
    return nil if project_id.blank?

    VideoEditorProject.find_by(id: project_id, user: current_user)
  end

  def persist_project_media!(project)
    entries = media_manifest_entries
    return project_media_payload(project) if entries.blank? && uploaded_media_files.blank?

    entries = uploaded_media_files.each_with_index.map { |file, index| { "upload_index" => index, "duration" => 0 } } if entries.blank?
    project.media_files.detach

    entries.filter_map do |entry|
      blob = if entry["blob_id"].present?
        ActiveStorage::Blob.find_by(id: entry["blob_id"])
      elsif entry.key?("upload_index")
        create_blob_from_upload(uploaded_media_files[entry["upload_index"].to_i])
      end

      next unless blob

      project.media_files.attach(blob)
      {
        "blob_id" => blob.id,
        "name" => blob.filename.to_s,
        "content_type" => blob.content_type.to_s,
        "byte_size" => blob.byte_size,
        "duration" => entry["duration"].to_f
      }
    end
  end

  def create_blob_from_upload(upload)
    return nil unless upload

    upload.tempfile.rewind if upload.respond_to?(:tempfile) && upload.tempfile.respond_to?(:rewind)
    ActiveStorage::Blob.create_and_upload!(
      io: upload.tempfile,
      filename: upload.original_filename,
      content_type: upload.content_type
    )
  end

  def project_media_payload(project)
    project.media_files.map do |attachment|
      blob = attachment.blob
      {
        "blob_id" => blob.id,
        "name" => blob.filename.to_s,
        "content_type" => blob.content_type.to_s,
        "byte_size" => blob.byte_size,
        "url" => rails_blob_path(blob, only_path: true)
      }
    end
  end

  def project_data_payload(media_manifest)
    {
      title: params[:title],
      orientation: params[:orientation],
      project_duration: params[:project_duration],
      item_order: params[:item_order],
      durations: params[:durations],
      start_times: params[:start_times],
      transitions: params[:transitions],
      transition_durations: params[:transition_durations],
      timeline_data: params[:timeline_data],
      media_manifest: media_manifest,
      captions: params[:captions],
      caption_layers: params[:caption_layers],
      caption_positions: params[:caption_positions],
      caption_x: params[:caption_x],
      caption_y: params[:caption_y],
      caption_sizes: params[:caption_sizes],
      caption_weights: params[:caption_weights],
      caption_colors: params[:caption_colors],
      caption_backgrounds: params[:caption_backgrounds]
    }
  end
end
