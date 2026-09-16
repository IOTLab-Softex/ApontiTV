class Users::SessionsController < Devise::SessionsController
  respond_to :html, :json

  def create
    self.resource = resource_class.authenticate_with_andar360(login_email, login_password)

    if resource&.active_for_authentication?
      resource.remember_me = remember_me_requested?
      sign_in(resource_name, resource)
      destination = resource.must_change_shared_password? ? edit_shared_password_path : after_sign_in_path_for(resource)
      respond_to do |format|
        format.json { render json: { redirect_url: destination } }
        format.html { redirect_to destination }
      end
    else
      render_login_error(invalid_login_message, :unauthorized)
    end
  rescue Andar360Identity::Unavailable
    render_login_error("O Andar360 está indisponível. Tente novamente em instantes.", :service_unavailable)
  rescue ActiveRecord::RecordInvalid, ActiveRecord::RecordNotUnique
    render_login_error("Não foi possível vincular sua conta. Fale com o administrador.", :unprocessable_entity)
  end

  private

  def render_login_error(message, status)
    respond_to do |format|
      format.json { render json: { error: message }, status: status }
      format.html do
        self.resource = resource_class.new(email: login_email)
        flash.now[:alert] = message
        render :new, status: status
      end
    end
  end

  def invalid_login_message
    "CPF/e-mail ou senha inválidos, ou acesso ao Aponti TV não autorizado no Andar360."
  end

  def remember_me_requested?
    ActiveModel::Type::Boolean.new.cast(params.dig(resource_name, :remember_me))
  end

  def login_email
    params.dig(resource_name, :email).to_s.downcase.strip
  end

  def login_password
    params.dig(resource_name, :password).to_s
  end
end
