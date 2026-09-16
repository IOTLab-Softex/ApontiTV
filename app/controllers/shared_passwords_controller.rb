class SharedPasswordsController < ApplicationController
  layout "authentication"
  skip_before_action :check_license

  def edit
    flash.delete(:alert) if flash[:alert] == I18n.t("devise.failure.already_authenticated")
    render json: { redirect_url: edit_shared_password_path(format: :html) } if request.format.json?
  end

  def update
    if !current_user.local_root? && current_user.andar360_offline?
      flash.now[:alert] = "A alteração de senha está bloqueada enquanto o Andar360 estiver indisponível."
      return render :edit, status: :service_unavailable
    end

    unless password_change_authorized?
      flash.now[:alert] = "Senha atual incorreta."
      return render :edit, status: :unprocessable_entity
    end

    if current_user.local_root?
      return update_local_root_password
    end

    identity = Andar360Identity.call("change_password",
      user_id: current_user.andar360_user_id,
      session_version: current_user.andar360_identity.fetch("session_version"),
      password: params[:password], password_confirmation: params[:password_confirmation])
    unless identity
      sign_out(current_user)
      return redirect_to new_user_session_path, alert: "Sua sessão expirou. Entre novamente."
    end
    user = User.from_andar360(identity)
    bypass_sign_in(user)
    redirect_to root_path, notice: "Nova senha salva. Use esta senha também no Andar360, caso tenha acesso."
  rescue Andar360Identity::InvalidPassword, Andar360Identity::Unavailable => error
    flash.now[:alert] = error.message
    render :edit, status: :unprocessable_entity
  end

  private

  def password_change_authorized?
    return true if current_user.must_change_shared_password?

    if current_user.local_root?
      return current_user.valid_password?(params[:current_password].to_s)
    end

    identity = Andar360Identity.authenticate(current_user.email, params[:current_password].to_s)
    identity.present? && identity["id"] == current_user.andar360_user_id
  rescue Andar360Identity::Unavailable
    false
  end

  def update_local_root_password
    if current_user.update(password: params[:password], password_confirmation: params[:password_confirmation])
      bypass_sign_in(current_user)
      redirect_to root_path, notice: "Nova senha salva."
    else
      flash.now[:alert] = current_user.errors.full_messages.join(". ")
      render :edit, status: :unprocessable_entity
    end
  end
end
