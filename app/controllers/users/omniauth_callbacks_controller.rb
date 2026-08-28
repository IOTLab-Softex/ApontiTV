class Users::OmniauthCallbacksController < Devise::OmniauthCallbacksController
  skip_before_action :authenticate_user!, only: [:google_oauth2, :failure]

  def google_oauth2
    unless StreamingConfiguration.google_oauth_ready?
      redirect_to new_user_session_path(locale: I18n.locale), alert: "Login com Google nao esta habilitado."
      return
    end

    user = User.from_google_oauth(request.env["omniauth.auth"])

    if user
      sign_in_and_redirect user, event: :authentication
      set_flash_message(:notice, :success, kind: "Google") if is_navigational_format?
    else
      redirect_to new_user_session_path(locale: I18n.locale), alert: "Este e-mail do Google nao esta cadastrado no sistema."
    end
  end

  def failure
    redirect_to new_user_session_path(locale: I18n.locale), alert: "Nao foi possivel entrar com Google. Tente novamente."
  end
end
