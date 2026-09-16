class Users::OmniauthCallbacksController < Devise::OmniauthCallbacksController
  skip_before_action :authenticate_user!, only: [:google_oauth2, :failure]

  def google_oauth2
    unless StreamingConfiguration.google_oauth_ready?
      redirect_to new_user_session_path(locale: I18n.locale), alert: "Login com Google nao esta habilitado."
      return
    end

    user = User.from_google_oauth(request.env["omniauth.auth"])

    if user&.active_for_authentication?
      sign_in_and_redirect user, event: :authentication
      set_flash_message(:notice, :success, kind: "Google") if is_navigational_format?
    else
      redirect_to new_user_session_path(locale: I18n.locale), alert: "Esta conta precisa estar cadastrada e autorizada para o Aponti TV no Andar360."
    end
  rescue Andar360Identity::Unavailable
    redirect_to new_user_session_path, alert: "O Andar360 está indisponível. Tente novamente em instantes."
  end

  def failure
    redirect_to new_user_session_path(locale: I18n.locale), alert: "Nao foi possivel entrar com Google. Tente novamente."
  end
end
