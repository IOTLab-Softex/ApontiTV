class SystemNotification < ApplicationRecord
  serialize :metadata, JSON

  SEVERITIES = %w[info success warning error].freeze

  validates :title, :event_key, presence: true
  validates :severity, inclusion: { in: SEVERITIES }

  scope :recent, -> { order(created_at: :desc) }
  scope :unread, -> { where(read_at: nil) }

  belongs_to :source, polymorphic: true, optional: true

  def self.emit!(event_key:, title:, message: nil, severity: "info", source: nil, metadata: {}, dedupe_for: 2.minutes)
    normalized_severity = severity.to_s.in?(SEVERITIES) ? severity.to_s : "info"
    source_type = source&.class&.name
    source_id = source&.id

    duplicate = where(
      event_key: event_key,
      source_type: source_type,
      source_id: source_id,
      read_at: nil
    ).where("created_at >= ?", dedupe_for.ago).first

    return duplicate if duplicate.present?

    create!(
      title: title,
      message: message,
      severity: normalized_severity,
      event_key: event_key,
      source: source,
      metadata: metadata.presence || {}
    )
  rescue StandardError => error
    Rails.logger.warn("[SystemNotification] #{error.class}: #{error.message}")
    nil
  end

  def icon_class
    case severity
    when "success"
      "fa-check-circle"
    when "warning"
      "fa-exclamation-triangle"
    when "error"
      "fa-times-circle"
    else
      "fa-info-circle"
    end
  end
end
