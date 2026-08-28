class AgentPairingsController < ActionController::API
  def create
    secret = params[:pairing_secret].to_s
    if secret.length < 32
      render json: { ok: false, error: "invalid_pairing_secret" }, status: :unprocessable_entity
      return
    end

    digest = DesktopAgent.digest_token(secret)
    agent = DesktopAgent.find_or_initialize_by(pairing_secret_digest: digest)
    agent.assign_attributes(
      name: params[:name].presence || params[:hostname].presence || "Computador Windows",
      hostname: params[:hostname].to_s.presence,
      ip_address: request.remote_ip,
      agent_version: params[:agent_version].to_s.presence,
      last_seen_at: Time.current,
      enabled: true
    )
    agent.status = "pending" if agent.new_record?
    agent.save!
    token = agent.approved? ? agent.issue_token! : nil

    render json: {
      ok: true,
      id: agent.id,
      status: agent.status,
      approval_code: agent.approval_code,
      approved: agent.approved?,
      token: token
    }
  end

  def show
    agent = DesktopAgent.find_by(id: params[:id])
    secret = params[:pairing_secret].to_s

    unless agent && agent.pairing_secret_digest == DesktopAgent.digest_token(secret)
      render json: { ok: false, error: "not_found" }, status: :not_found
      return
    end

    agent.update!(
      last_seen_at: Time.current,
      ip_address: request.remote_ip,
      agent_version: params[:agent_version].to_s.presence || agent.agent_version
    )

    token = agent.approved? ? agent.issue_token! : nil

    render json: {
      ok: true,
      id: agent.id,
      status: agent.status,
      approval_code: agent.approval_code,
      approved: agent.approved?,
      token: token
    }
  end
end
