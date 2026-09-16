class TvMonitoringController < ActionController::API
  def index
    configured = ENV["APONTI_MONITORING_TOKEN"].presence
    token_file = Rails.root.join("config", "monitoring.token")
    configured ||= token_file.read.strip if token_file.file?
    return head :service_unavailable if configured.blank?

    supplied = request.authorization.to_s.delete_prefix("Bearer ")
    return head :unauthorized unless ActiveSupport::SecurityUtils.secure_compare(configured, supplied)

    response.headers["Cache-Control"] = "no-store"
    render json: TvMonitoringSnapshot.call
  end
end
