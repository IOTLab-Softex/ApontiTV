class AgentWallpapersController < ActionController::API
  before_action :authenticate_agent!

  def show
    @agent.mark_seen!(
      request: request,
      hostname: request.headers["X-Aponti-Agent-Hostname"],
      agent_version: request.headers["X-Aponti-Agent-Version"]
    )

    render json: DesktopWallpaperPayload.new(request: request).as_json
  end

  def applied
    version = params[:version].to_s.strip
    @agent.mark_wallpaper_applied!(version) if version.present?

    render json: { ok: true }
  end

  private

  def authenticate_agent!
    token = request.authorization.to_s.sub(/\ABearer\s+/i, "")
    @agent = DesktopAgent.authenticate(token)

    return if @agent.present?

    render json: { ok: false, error: "unauthorized" }, status: :unauthorized
  end
end
