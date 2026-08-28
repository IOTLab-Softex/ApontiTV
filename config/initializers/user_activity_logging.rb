Warden::Manager.after_set_user except: :fetch do |user, auth, _opts|
  next unless user.respond_to?(:update_columns)

  request = auth.request
  now = Time.current

  user.update_columns(last_accessed_at: now, updated_at: now)

  UserActivityLog.create!(
    user: user,
    user_email: user.email.to_s,
    action_key: "sessions.login",
    action_label: "Entrou no sistema",
    controller_name: "sessions",
    action_name: "login",
    request_method: request.request_method,
    path: request.fullpath,
    ip_address: request.remote_ip,
    user_agent: request.user_agent.to_s.truncate(500),
    status: 200,
    occurred_at: now
  )
rescue StandardError => error
  Rails.logger.warn("[UserActivityLog login] #{error.class}: #{error.message}")
end

Warden::Manager.before_logout do |user, auth, _opts|
  next unless user.respond_to?(:update_columns)

  request = auth.request
  now = Time.current

  user.update_columns(last_signed_out_at: now, updated_at: now)

  UserActivityLog.create!(
    user: user,
    user_email: user.email.to_s,
    action_key: "sessions.logout",
    action_label: "Saiu do sistema",
    controller_name: "sessions",
    action_name: "logout",
    request_method: request.request_method,
    path: request.fullpath,
    ip_address: request.remote_ip,
    user_agent: request.user_agent.to_s.truncate(500),
    status: 200,
    occurred_at: now
  )
rescue StandardError => error
  Rails.logger.warn("[UserActivityLog logout] #{error.class}: #{error.message}")
end
