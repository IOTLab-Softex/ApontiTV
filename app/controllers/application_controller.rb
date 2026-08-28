class ApplicationController < ActionController::Base
  include Pundit

  rescue_from ActionController::InvalidAuthenticityToken, with: :handle_invalid_authenticity_token

  before_action :set_locale
  before_action :authenticate_user!
  before_action :check_license, unless: :skip_license_check?
  after_action :record_user_activity_log, if: :record_user_activity_log?

  def change_locale
    locale = params[:locale].presence_in(I18n.available_locales.map(&:to_s)) || I18n.default_locale.to_s
    session[:locale] = locale

    redirect_to localized_return_path(locale)
  end

  private

  def set_locale
    locale = params[:locale].presence_in(I18n.available_locales.map(&:to_s)) || session[:locale] || I18n.default_locale
    I18n.locale = locale
    session[:locale] = I18n.locale
  end

  def default_url_options
    { locale: I18n.locale }
  end

  def localized_return_path(locale)
    target = params[:return_to].presence || request.referer || root_path(locale: locale)
    uri = URI.parse(target)
    return root_path(locale: locale) if uri.host.present? && uri.host != request.host

    query_params = Rack::Utils.parse_nested_query(uri.query)
    query_params["locale"] = locale
    uri.query = query_params.to_query.presence
    uri.to_s
  rescue URI::InvalidURIError
    root_path(locale: locale)
  end

  def check_license
    redirect_to licenca_path unless LicenseValidator.valid?
  end

  def skip_license_check?
    controller_name.in?(%w[sessions passwords registrations license]) || devise_controller?
  end

  def handle_invalid_authenticity_token
    reset_session
    redirect_to new_user_session_path(locale: I18n.locale), alert: "Sua sessao expirou. Recarregue a pagina e entre novamente."
  end

  def record_user_activity_log?
    return false unless user_signed_in?
    return false if controller_name == "access_logs"
    return false if controller_name == "notifications"
    return false if request.path.start_with?("/rails/active_storage")
    return false if request.get? && request.format.json?
    return false if controller_path.start_with?("agent")

    ignored_actions = {
      "broadcasts" => %w[
        check_tv_status
        mobile_index
        mobile_status
        mobile_presence
        mobile_player_status
        mobile_thumbnail
        mobile_video
        mobile_prepared_video
        mobile_playlist_item
        preview_stream
      ],
      "wallpapers" => %w[status]
    }

    !ignored_actions.fetch(controller_name, []).include?(action_name)
  end

  def record_user_activity_log
    UserActivityLog.create!(
      user: current_user,
      user_email: current_user.email.to_s,
      action_key: "#{controller_name}.#{action_name}",
      action_label: user_activity_action_label,
      controller_name: controller_name,
      action_name: action_name,
      request_method: request.request_method,
      path: request.fullpath,
      ip_address: request.remote_ip,
      user_agent: request.user_agent.to_s.truncate(500),
      status: response.status,
      record_type: user_activity_record_type,
      record_id: params[:id].presence,
      request_params: user_activity_filtered_params,
      occurred_at: Time.current
    )
  rescue StandardError => error
    Rails.logger.warn("[UserActivityLog] #{error.class}: #{error.message}")
  end

  def user_activity_action_label
    controller_label = controller_name.to_s.humanize

    case action_name
    when "index"
      "Acessou #{controller_label}"
    when "show"
      "Visualizou #{controller_label.singularize}"
    when "new"
      "Abriu cadastro de #{controller_label.singularize}"
    when "edit"
      "Abriu edicao de #{controller_label.singularize}"
    when "create"
      "Criou #{controller_label.singularize}"
    when "update"
      "Atualizou #{controller_label.singularize}"
    when "destroy"
      "Excluiu #{controller_label.singularize}"
    when "start"
      "Iniciou transmissao"
    when "stop"
      "Parou transmissao"
    when "publish_video"
      "Publicou playlist nas TVs"
    when "upload_media"
      "Enviou midia para biblioteca"
    when "set_wallpaper"
      "Aplicou wallpaper"
    when "set_volume_tv", "mute_tv", "volume_up_tv", "volume_down_tv"
      "Alterou volume da TV"
    when "power_on_tv"
      "Ligou TV"
    when "power_off_tv"
      "Desligou TV"
    when "set_android_launcher"
      "Definiu Aponti como launcher"
    when "remove_android_launcher"
      "Removeu Aponti como launcher"
    when "change_locale"
      "Alterou idioma"
    else
      "#{request.request_method} #{controller_label}##{action_name}"
    end
  end

  def user_activity_record_type
    return controller_name.classify if params[:id].present?

    nil
  end

  def user_activity_filtered_params
    filtered = request.filtered_parameters.except(
      "controller",
      "action",
      "authenticity_token",
      "password",
      "password_confirmation",
      "current_password"
    )

    filtered.to_json.truncate(3000)
  rescue StandardError
    nil
  end
end
