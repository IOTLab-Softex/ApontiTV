class SavedBroadcastPlaylistItem < ApplicationRecord
  belongs_to :saved_broadcast_playlist, inverse_of: :items
  belongs_to :media_blob, class_name: "ActiveStorage::Blob"

  MEDIA_TYPES = %w[video image].freeze
  TRANSITION_STYLES = SavedBroadcastPlaylist::TRANSITION_STYLES

  validates :media_type, inclusion: { in: MEDIA_TYPES }
  validates :position, numericality: { only_integer: true, greater_than_or_equal_to: 0 }
  validates :duration_seconds, numericality: { only_integer: true, greater_than: 0, less_than_or_equal_to: 3600 }
  validates :transition_style, inclusion: { in: TRANSITION_STYLES }
  validates :transition_duration_ms, numericality: { only_integer: true, greater_than_or_equal_to: 0, less_than_or_equal_to: 10_000 }

  before_validation :infer_media_type_from_blob

  def video?
    media_type == "video"
  end

  def image?
    media_type == "image"
  end

  def as_editor_json(view_context, preview_url_builder: nil)
    {
      id: id,
      blob_id: media_blob_id,
      type: media_type,
      filename: media_blob.filename.to_s,
      size_label: view_context.number_to_human_size(media_blob.byte_size),
      content_type: media_blob.content_type,
      preview_url: preview_url_builder ? preview_url_builder.call(media_blob) : nil,
      duration_seconds: duration_seconds,
      video_duration_mode: saved_broadcast_playlist.video_duration_mode,
      transition_style: transition_style,
      transition_duration_ms: transition_duration_ms
    }
  end

  private

  def infer_media_type_from_blob
    return if media_blob.blank?

    self.media_type = media_blob.content_type.to_s.start_with?("image/") ? "image" : "video"
  end
end
