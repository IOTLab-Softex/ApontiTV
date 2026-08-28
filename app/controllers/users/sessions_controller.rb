class Users::SessionsController < Devise::SessionsController
  respond_to :html, :json

  def create
    return super unless request.format.json?

    self.resource = resource_class.find_for_database_authentication(email: login_email)

    if resource&.valid_password?(login_password)
      resource.remember_me = remember_me_requested?
      sign_in(resource_name, resource)
      render json: { redirect_url: after_sign_in_path_for(resource) }
    else
      render json: { error: invalid_login_message }, status: :unauthorized
    end
  end

  private

  def invalid_login_message
    I18n.t(
      "devise.failure.invalid",
      authentication_keys: resource_class.authentication_keys.join(", ")
    )
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
