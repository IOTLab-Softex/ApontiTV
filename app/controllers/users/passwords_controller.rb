class Users::PasswordsController < Devise::PasswordsController
  # A recupera??o ? centralizada no Andar360. Links antigos podem chegar ao
  # Aponti TV na rota do Devise; quando trouxerem um token, ele ? preservado e
  # enviado diretamente para o formul?rio de nova senha do Andar360.
  def new
    redirect_to Andar360Identity.recovery_url(request), allow_other_host: true
  end

  def edit
    token = params[:reset_password_token].to_s
    destination = token.present? ? Andar360Identity.password_reset_url(request, token) : Andar360Identity.recovery_url(request)
    redirect_to destination, allow_other_host: true
  end

  alias_method :create, :new
  alias_method :update, :new
end
