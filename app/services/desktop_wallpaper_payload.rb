require "digest"

class DesktopWallpaperPayload
  FILENAME = "wallpaper.jpeg".freeze

  def initialize(request:, group: nil)
    @request = request
    @group = group
  end

  def available?
    File.exist?(path)
  end

  def version
    return nil unless available?

    "#{File.mtime(path).to_i}-#{sha256[0, 12]}"
  end

  def as_json
    return { available: false } unless available?

    {
      available: true,
      version: version,
      filename: FILENAME,
      content_type: "image/jpeg",
      sha256: sha256,
      url: url
    }
  end

  private

  attr_reader :request, :group

  def path
    group.present? && File.exist?(group.wallpaper_path) ? group.wallpaper_path : Rails.root.join("public", "wallpapers", FILENAME)
  end

  def sha256
    @sha256 ||= Digest::SHA256.file(path).hexdigest
  end

  def url
    relative = group.present? && File.exist?(group.wallpaper_path) ? "wallpapers/groups/#{group.id}.jpeg" : "wallpapers/#{FILENAME}"
    "#{request.protocol}#{request.host_with_port}/#{relative}?v=#{version}"
  end
end
