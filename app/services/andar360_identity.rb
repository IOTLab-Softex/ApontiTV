require "net/http"
require "json"

class Andar360Identity
  class Unavailable < StandardError; end
  class InvalidPassword < StandardError; end

  def self.authenticate(login, password)
    call("authenticate", login: login, password: password)
  end

  def self.authorize(user_id: nil, login: nil)
    call("authorize", user_id: user_id, login: login)
  end

  def self.recovery_url(request)
    "#{public_url(request)}/recuperar-senha"
  end

  def self.password_reset_url(request, token)
    "#{public_url(request)}/users/password/edit?#{URI.encode_www_form(reset_password_token: token)}"
  end

  def self.public_url(request)
    if request.host.in?(%w[localhost 127.0.0.1 ::1])
      "http://localhost:8081"
    else
      ENV.fetch("ANDAR360_PUBLIC_URL", "http://andar360.ddns.net")
    end
  end

  def self.call(action, attributes)
    token = ENV["ANDAR360_INTEGRATION_TOKEN"].presence || begin
      path = Rails.root.join("storage", "andar360_integration_token")
      File.read(path).strip if File.file?(path)
    end
    raise Unavailable, "Integracao Andar360 nao configurada" if token.blank?

    uri = URI("#{ENV.fetch('ANDAR360_INTERNAL_URL', 'http://127.0.0.1:3001')}/integrations/aponti_tv/#{action}")
    unless uri.scheme == "https" || (uri.scheme == "http" && uri.host.in?(%w[127.0.0.1 localhost ::1]))
      raise Unavailable, "A conexao com Andar360 exige HTTPS fora da maquina local"
    end
    request = Net::HTTP::Post.new(uri)
    request["Authorization"] = "Bearer #{token}"
    request["Content-Type"] = "application/json"
    request.body = JSON.generate(attributes)
    response = Net::HTTP.start(uri.host, uri.port, nil, use_ssl: uri.scheme == "https", open_timeout: 2, read_timeout: 5) { |http| http.request(request) }
    return nil if response.code == "401"
    return nil if action == "photo" && response.code == "404"
    if action == "change_password" && response.code == "422"
      raise InvalidPassword, Array(JSON.parse(response.body)["errors"]).join(" ")
    end
    raise Unavailable, "Andar360 indisponivel" unless response.is_a?(Net::HTTPSuccess)

    identity = JSON.parse(response.body)
    return identity if action == "photo"
    unless identity["allowed"] == true && identity["id"].is_a?(Integer) &&
        identity["email"].present? && identity["session_version"].to_s.match?(/\A[0-9a-f]{64}\z/)
      raise Unavailable, "Resposta invalida do Andar360"
    end
    identity
  rescue IOError, SystemCallError, Timeout::Error, JSON::ParserError, SocketError => error
    Rails.logger.warn("[Andar360] #{error.class}")
    raise Unavailable, "Nao foi possivel consultar o Andar360"
  end
end
