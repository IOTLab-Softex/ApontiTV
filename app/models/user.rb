class User < ApplicationRecord
  ANDAR360_OFFLINE_ACCESS_FOR = 30.days
  # Include default devise modules. Others available are:
  # :confirmable, :lockable, :timeoutable, :trackable and :omniauthable
  devise :database_authenticatable, :registerable,
         :recoverable, :rememberable, :validatable,
         :omniauthable, omniauth_providers: [:google_oauth2]

  has_many :user_activity_logs, dependent: :nullify

  def self.authenticate_with_andar360(login, password)
    normalized = login.to_s.strip.downcase
    root_email = ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local").strip.downcase
    if normalized == "root" || normalized == root_email
      root = find_by("LOWER(email) = ?", root_email)
      return root if root&.local_root? && root.valid_password?(password)
      return nil
    end
    identity = Andar360Identity.authenticate(login, password)
    user = from_andar360(identity) if identity
    user&.cache_andar360_identity!(identity, password: password, login: login)
    user
  rescue Andar360Identity::Unavailable
    cached_user, had_cached_account = authenticate_offline(login, password)
    return cached_user if cached_user
    return nil if had_cached_account
    raise
  end

  def self.authenticate_offline(login, password)
    normalized = login.to_s.strip.downcase
    user = where.not(andar360_user_id: nil)
      .where("LOWER(email) = ? OR LOWER(andar360_login) = ?", normalized, normalized).first
    return [nil, false] unless user
    return [nil, true] unless user.offline_andar360_access_available? && user.valid_password?(password)

    user.instance_variable_set(:@andar360_offline, true)
    [user, true]
  end

  def self.from_andar360(identity)
    return unless identity && identity["allowed"] == true

    attempts = 0
    begin
      user = find_by(andar360_user_id: identity.fetch("id")) ||
        where(andar360_user_id: nil).find_by("LOWER(email) = ?", identity.fetch("email").downcase) || new(admin: false)
      return nil if user.local_root?
      user.andar360_user_id = identity.fetch("id")
      user.email = identity.fetch("email")
      user.password = SecureRandom.hex(32) if user.new_record?
      user.save! if user.changed?
      user.instance_variable_set(:@andar360_identity, identity)
      user
    rescue ActiveRecord::StatementInvalid => error
      # Retry the entire operation without holding a read transaction while
      # BCrypt runs; SQLite cannot upgrade a stale read snapshot to a writer.
      raise unless error.cause.is_a?(SQLite3::BusyException) && (attempts += 1) <= 3
      sleep(0.05 * attempts)
      retry
    end
  end

  def andar360_identity
    return @andar360_identity if defined?(@andar360_identity)
    @andar360_identity = andar360_user_id.present? ? Andar360Identity.authorize(user_id: andar360_user_id) : nil
    cache_andar360_identity!(@andar360_identity) if @andar360_identity
  rescue Andar360Identity::Unavailable
    @andar360_offline = true
    @andar360_identity = cached_andar360_identity
    raise unless @andar360_identity
    @andar360_identity
  end

  def active_for_authentication?
    super && (local_root? || andar360_identity.present?)
  end

  def local_root?
    admin? && andar360_user_id.nil? &&
      email.to_s.downcase == ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local").strip.downcase
  end

  def must_change_shared_password?
    !local_root? && andar360_identity&.fetch("force_password_change", false) == true
  end

  def offline_andar360_access_available?
    andar360_user_id.present? && andar360_last_verified_at.present? &&
      andar360_last_verified_at >= ANDAR360_OFFLINE_ACCESS_FOR.ago &&
      andar360_session_version.present?
  end

  def andar360_offline?
    @andar360_offline == true
  end

  def cache_andar360_identity!(identity, password: nil, login: nil)
    return unless identity

    self.andar360_last_verified_at = Time.current
    self.andar360_session_version = identity.fetch("session_version")
    self.andar360_force_password_change = identity.fetch("force_password_change", false)
    self.andar360_login = login.to_s.strip.downcase if login.present?
    self.password = password if password.present?
    save! if changed?
    @andar360_offline = false
    @andar360_identity = identity
  end

  def valid_password?(password)
    return super if local_root?
    return false unless andar360_user_id.present?
    identity = Andar360Identity.authenticate(email, password)
    if identity.present? && identity["id"] == andar360_user_id
      cache_andar360_identity!(identity, password: password)
      true
    else
      false
    end
  rescue Andar360Identity::Unavailable
    offline_andar360_access_available? && super
  end

  # Devise checks this on session and remember-cookie restoration. A password
  # reset, permission removal or blocked account invalidates existing sessions.
  def authenticatable_salt
    return super if local_root?
    identity = andar360_identity
    identity ? "andar360:#{identity.fetch('id')}:#{identity.fetch('session_version')}" : nil
  end

  def self.serialize_from_session(key, salt)
    user = super
    user if user&.active_for_authentication?
  end

  def self.from_google_oauth(auth)
    email = auth.info.email.to_s.downcase.strip
    return nil if email.blank?

    return nil unless auth.extra&.raw_info&.email_verified == true
    identity = Andar360Identity.authorize(login: email)
    from_andar360(identity) if identity
  end

  private

  def cached_andar360_identity
    return unless offline_andar360_access_available?

    {
      "id" => andar360_user_id,
      "email" => email,
      "allowed" => true,
      "session_version" => andar360_session_version,
      "force_password_change" => andar360_force_password_change
    }
  end
end
