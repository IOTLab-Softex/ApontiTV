class DesktopAgentsController < ApplicationController
  before_action :require_admin!
  before_action :set_desktop_agent, only: [:destroy, :approve]

  def index
    @desktop_agents = DesktopAgent.enabled.order(Arel.sql("last_seen_at IS NULL"), last_seen_at: :desc, name: :asc)
    @desktop_agent = DesktopAgent.new
  end

  def create
    @desktop_agent = DesktopAgent.new(desktop_agent_params)

    if @desktop_agent.save
      redirect_to desktop_agents_path, notice: "Agente criado. Token: #{@desktop_agent.plain_token}"
    else
      @desktop_agents = DesktopAgent.enabled.order(Arel.sql("last_seen_at IS NULL"), last_seen_at: :desc, name: :asc)
      render :index, status: :unprocessable_entity
    end
  end

  def destroy
    @desktop_agent.destroy!
    redirect_to desktop_agents_path, notice: "Agente excluido."
  end

  def approve
    @desktop_agent.approve!
    redirect_to desktop_agents_path, notice: "Computador aprovado. O instalador recebera o token automaticamente."
  end

  private

  def require_admin!
    return if current_user&.admin?

    redirect_to root_path, alert: "Apenas administradores podem gerenciar agentes."
  end

  def set_desktop_agent
    @desktop_agent = DesktopAgent.find(params[:id])
  end

  def desktop_agent_params
    params.require(:desktop_agent).permit(:name)
  end
end
