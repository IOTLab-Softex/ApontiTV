class DesktopGroupsController < ApplicationController
  before_action :require_admin!
  before_action :set_group, only: [:destroy]

  def create
    group = DesktopGroup.new(group_params)
    if group.save
      redirect_to desktop_agents_path(tab: "groups"), notice: "Grupo criado com sucesso."
    else
      redirect_to desktop_agents_path(tab: "groups"), alert: group.errors.full_messages.to_sentence
    end
  end

  def destroy
    @group.destroy!
    redirect_to desktop_agents_path(tab: "groups"), notice: "Grupo removido. Os computadores ficaram sem grupo."
  end

  private

  def group_params
    params.require(:desktop_group).permit(:name)
  end

  def set_group
    @group = DesktopGroup.find(params[:id])
  end

  def require_admin!
    redirect_to(root_path, alert: "Apenas administradores podem gerenciar grupos.") unless current_user&.admin?
  end
end
