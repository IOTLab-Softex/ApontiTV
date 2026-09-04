class DesktopGroup < ApplicationRecord
  has_many :desktop_agents, dependent: :nullify

  validates :name, presence: true, uniqueness: { case_sensitive: false }

  def wallpaper_path
    Rails.root.join("public", "wallpapers", "groups", "#{id}.jpeg")
  end
end
