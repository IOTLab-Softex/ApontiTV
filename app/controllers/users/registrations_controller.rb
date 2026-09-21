class Users::RegistrationsController < Devise::RegistrationsController
  def edit
    self.resource = current_user
    set_minimum_password_length
    render "devise/registrations/edit"
  end

  def update
    if !current_user.local_root? && current_user.andar360_offline?
      current_user.errors.add(:base, "A alteração de senha está bloqueada enquanto o Andar360 estiver indisponível.")
      self.resource = current_user
      set_minimum_password_length
      return render :edit, status: :service_unavailable
    end

    unless current_user.valid_password?(params.dig(:user, :current_password).to_s)
      current_user.errors.add(:current_password, "está incorreta")
      self.resource = current_user
      set_minimum_password_length
      return render :edit, status: :unprocessable_entity
    end

    if current_user.local_root?
      return update_local_root_password
    end

    identity = Andar360Identity.call(
      "change_password",
      user_id: current_user.andar360_user_id,
      session_version: current_user.andar360_identity.fetch("session_version"),
      password: params.dig(:user, :password),
      password_confirmation: params.dig(:user, :password_confirmation)
    )
    return redirect_to new_user_session_path, alert: "Sua sessão expirou. Entre novamente." unless identity

    user = User.from_andar360(identity)
    bypass_sign_in(user)
    redirect_to root_path, notice: "Nova senha salva."
  rescue Andar360Identity::InvalidPassword, Andar360Identity::Unavailable => error
    current_user.errors.add(:base, error.message)
    self.resource = current_user
    set_minimum_password_length
    render :edit, status: :unprocessable_entity
  end

  private

  def update_local_root_password
    if current_user.update(password: params.dig(:user, :password), password_confirmation: params.dig(:user, :password_confirmation))
      bypass_sign_in(current_user)
      redirect_to root_path, notice: "Nova senha salva."
    else
      self.resource = current_user
      set_minimum_password_length
      render :edit, status: :unprocessable_entity
    end
  end
end
