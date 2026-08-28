require "fileutils"

class WallpapersController < ApplicationController
  def new
  end

  def create
    wallpaper = params[:wallpaper]

    unless wallpaper.present?
      respond_wallpaper_error("Selecione uma imagem para usar como wallpaper.")
      return
    end

    unless wallpaper.content_type.to_s.start_with?("image/")
      respond_wallpaper_error("Envie apenas arquivos de imagem.")
      return
    end

    wallpapers_dir = Rails.root.join("public", "wallpapers")
    FileUtils.mkdir_p(wallpapers_dir)
    File.binwrite(wallpapers_dir.join("wallpaper.jpeg"), wallpaper.read)

    payload = DesktopWallpaperPayload.new(request: request).as_json

    respond_to do |format|
      format.html { redirect_back fallback_location: broadcasts_path, notice: "Wallpaper atualizado com sucesso." }
      format.json do
        render json: {
          ok: true,
          notice: "Wallpaper atualizado com sucesso.",
          wallpaper: payload,
          progress: wallpaper_progress_for(payload[:version])
        }
      end
    end
  end

  def index
    @wallpapers = Dir[Rails.root.join("public", "wallpapers", "*")]
  end

  def status
    version = params[:version].presence || DesktopWallpaperPayload.new(request: request).version
    render json: wallpaper_progress_for(version)
  end

  private

  def respond_wallpaper_error(message)
    respond_to do |format|
      format.html { redirect_back fallback_location: broadcasts_path, alert: message }
      format.json { render json: { ok: false, error: message }, status: :unprocessable_entity }
    end
  end

  def wallpaper_progress_for(version)
    agents = DesktopAgent.enabled.where(status: "approved")
    online_agents = agents.select(&:online?)
    target_agents = online_agents.any? ? online_agents : agents.to_a
    applied_agents = target_agents.select { |agent| version.present? && agent.last_wallpaper_version.to_s == version.to_s }
    total = target_agents.size
    applied = applied_agents.size
    percent = total.positive? ? ((applied.to_f / total) * 100).round : 0

    {
      ok: true,
      version: version,
      total: total,
      applied: applied,
      pending: [total - applied, 0].max,
      online: online_agents.size,
      approved: agents.size,
      percent: percent,
      complete: total.positive? && applied >= total
    }
  end
end
