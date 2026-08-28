class UsersController < ApplicationController
  include Pundit

  before_action :authenticate_user!
  before_action :authorize_admin!
  before_action :set_user, only: [:edit, :update, :destroy]

  def index
    authorize User
    @users = User.all
  end

  def new
    @user = User.new
  end

  def create
    Rails.logger.debug "[CREATE] Tentando criar usuário com: #{params[:user].inspect}"
    @user = User.new(user_params)
    if @user.save
      redirect_to users_path, notice: "Usuário criado com sucesso!"
    else
      Rails.logger.debug "[CREATE] Erros ao criar usuário: #{@user.errors.full_messages}"
      flash.now[:alert] = "Erro ao criar o usuário."
      render :new
    end
  end
  
  

  def edit; end

  def update
    if @user.update(user_params)
      redirect_to users_path, notice: "Usuário atualizado com sucesso!"
    else
      flash.now[:alert] = "Erro ao atualizar usuário."
      render :edit
    end
  end

  def destroy
    if @user != current_user
      @user.destroy
      redirect_to users_path, notice: "Usuário excluído com sucesso!"
    else
      redirect_to users_path, alert: "Você não pode excluir a si mesmo."
    end
  end

  private

  def authorize_admin!
    redirect_to root_path, alert: "Acesso não autorizado" unless current_user.admin?
  end

  def set_user
    @user = User.find(params[:id])
  end

  def user_params
    permitted = params.require(:user).permit(:email, :password, :password_confirmation, :admin)

    if permitted[:password].blank?
      permitted.delete(:password)
      permitted.delete(:password_confirmation)
    end

    permitted
  end
end
