class SavedBroadcastPlaylist < ApplicationRecord
  has_many :items,
           -> { order(:position, :id) },
           class_name: "SavedBroadcastPlaylistItem",
           dependent: :destroy,
           inverse_of: :saved_broadcast_playlist

  TRANSITION_STYLES = %w[fade slide blur zoom wipe push flip cross_zoom aponti_smooth none].freeze
  VIDEO_DURATION_MODES = %w[image_duration video_end].freeze

  validates :name, presence: true
  validates :image_duration_seconds, numericality: { only_integer: true, greater_than: 0, less_than_or_equal_to: 3600 }
  validates :transition_style, inclusion: { in: TRANSITION_STYLES }
  validates :transition_duration_ms, numericality: { only_integer: true, greater_than_or_equal_to: 0, less_than_or_equal_to: 10_000 }
  validates :video_duration_mode, inclusion: { in: VIDEO_DURATION_MODES }
  validate :must_have_items

  def item_count
    items.size
  end

  def as_editor_json(view_context, preview_url_builder: nil)
    {
      id: id,
      name: name,
      image_duration_seconds: image_duration_seconds,
      video_duration_mode: video_duration_mode,
      transition_style: transition_style,
      transition_duration_ms: transition_duration_ms,
      sync_enabled: sync_enabled?,
      item_count: item_count,
      updated_at_label: updated_at.strftime("%d/%m/%Y %H:%M"),
      items: items.includes(:media_blob).map { |item| item.as_editor_json(view_context, preview_url_builder: preview_url_builder) }
    }
  end

  private

  def must_have_items
    errors.add(:base, "adicione pelo menos uma midia na playlist") if items.reject(&:marked_for_destruction?).blank?
  end
end
