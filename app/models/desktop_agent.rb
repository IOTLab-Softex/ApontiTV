require "digest"
require "securerandom"

class DesktopAgent < ApplicationRecord
  belongs_to :desktop_group, optional: true
  ONLINE_WINDOW = 5.minutes

  attr_reader :plain_token

  validates :name, presence: true
  validates :token_digest, uniqueness: true, allow_blank: true
  validates :pairing_secret_digest, uniqueness: true, allow_blank: true

  before_validation :ensure_token_digest, on: :create, unless: :pending?
  before_validation :ensure_pairing_fields, on: :create, if: :pending?

  def self.digest_token(token)
    Digest::SHA256.hexdigest(token.to_s)
  end

  def self.authenticate(token)
    digest = digest_token(token)
    enabled.where(status: "approved").find_by(token_digest: digest)
  end

  def self.enabled
    where(enabled: true)
  end

  def online?
    last_seen_at.present? && last_seen_at > ONLINE_WINDOW.ago
  end

  def pending?
    status.to_s == "pending"
  end

  def approved?
    status.to_s == "approved"
  end

  def approve!
    update!(status: "approved", approved_at: Time.current)
  end

  def issue_token_once!
    return nil unless approved?
    return nil if token_digest.present?

    issue_token!
  end

  def issue_token!
    return nil unless approved?

    token = SecureRandom.hex(32)
    update!(
      token_digest: self.class.digest_token(token),
      paired_at: Time.current
    )
    token
  end

  def mark_seen!(request:, hostname: nil, agent_version: nil)
    update!(
      hostname: hostname.presence || self.hostname,
      ip_address: request.remote_ip,
      agent_version: agent_version.presence || self.agent_version,
      last_seen_at: Time.current
    )
  end

  def mark_wallpaper_applied!(version)
    update!(
      last_wallpaper_version: version,
      last_wallpaper_applied_at: Time.current,
      last_seen_at: Time.current
    )
  end

  private

  def ensure_token_digest
    return if token_digest.present?

    @plain_token = SecureRandom.hex(32)
    self.token_digest = self.class.digest_token(@plain_token)
    self.status = "approved"
    self.approved_at ||= Time.current
  end

  def ensure_pairing_fields
    self.approval_code ||= SecureRandom.alphanumeric(6).upcase
  end
end
