class User < ApplicationRecord
  # Include default devise modules. Others available are:
  # :confirmable, :lockable, :timeoutable, :trackable and :omniauthable
  devise :database_authenticatable, :registerable,
         :recoverable, :rememberable, :validatable,
         :omniauthable, omniauth_providers: [:google_oauth2]

  has_many :user_activity_logs, dependent: :nullify

  def self.from_google_oauth(auth)
    email = auth.info.email.to_s.downcase.strip
    return nil if email.blank?

    where("LOWER(email) = ?", email).first
  end
end
