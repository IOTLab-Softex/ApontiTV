class BroadcastPlaylistItem < ApplicationRecord
  belongs_to :broadcast, touch: true
  has_one_attached :media

  MEDIA_TYPES = %w[video image].freeze
  VIDEO_DURATION_MODES = SavedBroadcastPlaylist::VIDEO_DURATION_MODES

  validates :media_type, inclusion: { in: MEDIA_TYPES }
  validates :position, numericality: { only_integer: true, greater_than_or_equal_to: 0 }
  validates :duration_seconds, numericality: { only_integer: true, greater_than: 0, less_than_or_equal_to: 3600 }
  validates :video_duration_mode, inclusion: { in: VIDEO_DURATION_MODES }
  validates :transition_style, inclusion: { in: SavedBroadcastPlaylist::TRANSITION_STYLES }, allow_blank: true
  validates :transition_duration_ms, numericality: { only_integer: true, greater_than_or_equal_to: 0, less_than_or_equal_to: 10_000 }, allow_nil: true
  validate :media_must_be_supported

  before_validation :infer_media_type_from_blob

  scope :ordered, -> { order(:position, :id) }

  def video?
    media_type == "video"
  end

  def image?
    media_type == "image"
  end

  private

  def infer_media_type_from_blob
    return unless media.attached?

    content_type = media.blob.content_type.to_s
    self.media_type =
      if content_type.start_with?("image/")
        "image"
      else
        "video"
      end
  end

  def media_must_be_supported
    unless media.attached?
      errors.add(:media, "precisa ser anexada")
      return
    end

    content_type = media.blob.content_type.to_s
    return if content_type.start_with?("video/") || content_type.start_with?("image/")

    errors.add(:media, "precisa ser video ou imagem")
  end
end
