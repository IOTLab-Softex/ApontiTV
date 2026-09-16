require "socket"
require "open3"
require "timeout"

class TvDeviceService
  Result = Struct.new(:success?, :message, keyword_init: true)

  DEFAULT_ADB_PORT = 5555
  OFFICIAL_APP_PACKAGE = "br.com.softextv.player"
  OFFICIAL_APP_MAIN_ACTIVITY = "br.com.softextv.player.MainActivity"
  GOOGLE_TV_LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx"
  GOOGLE_TV_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity"
  FIRE_TV_LAUNCHER_PACKAGE = "com.amazon.tv.launcher"
  FIRE_TV_HOME_ACTIVITY = ".ui.HomeActivity"
  STATUS_WAIT_SECONDS = 3
  POWER_COMMAND_DELAY_SECONDS = 2
  ADB_CANDIDATE_PATHS = [
    "adb",
    "C:/platform-tools/adb.exe",
    "C:/platform-tools/adb",
    "#{ENV['LOCALAPPDATA']}/Android/Sdk/platform-tools/adb.exe"
  ].freeze

  def initialize(broadcast)
    @broadcast = broadcast
  end

  def online?
    return false if target_host.blank?

    if adb_device?
      android_power_state == :on
    else
      ping_online?
    end
  end

  def reachable?
    return false if target_host.blank?

    if adb_device?
      adb_port_open?
    else
      ping_online?
    end
  end

  def power_state
    return :unavailable if target_host.blank?

    if adb_device?
      android_power_state
    else
      ping_online? ? :on : :off
    end
  end

  def power_on
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    if adb_device?
      current_state = android_power_state
      return Result.new(success?: true, message: "#{@broadcast.name} ja estava ligada via ADB.") if current_state == :on

      execute_adb_power_action(
        ["shell", "input", "keyevent", "KEYCODE_WAKEUP"],
        success_message: "Comando de ligar enviado para #{@broadcast.name}.",
        expected_online: true
      )
    else
      return Result.new(success?: false, message: "Informe o MAC da TV para Wake-on-LAN.") if @broadcast.tv_mac_address.blank?

      send_wol_packet
      verify_power_state(
        expected_online: true,
        success_message: "Pacote Wake-on-LAN enviado para #{@broadcast.name}.",
        failure_message: "A TV nao respondeu apos o Wake-on-LAN."
      )
    end
  rescue StandardError => e
    Result.new(success?: false, message: e.message)
  end

  def power_off
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    if adb_device?
      current_state = android_power_state
      return Result.new(success?: true, message: "#{@broadcast.name} ja estava desligada via ADB.") if current_state == :off

      execute_android_power_off
    else
      Result.new(success?: false, message: "TV normal usa Wake-on-LAN para ligar. O desligamento remoto depende do fabricante.")
    end
  end

  def volume_up
    execute_android_volume_key("KEYCODE_VOLUME_UP", "Volume aumentado em #{@broadcast.name}.")
  end

  def volume_down
    execute_android_volume_key("KEYCODE_VOLUME_DOWN", "Volume diminuido em #{@broadcast.name}.")
  end

  def mute
    execute_android_volume_key("KEYCODE_VOLUME_MUTE", "Mute alternado em #{@broadcast.name}.")
  end

  def current_volume_percent
    return nil unless adb_device?
    return nil if target_host.blank?

    connect_result = connect_adb
    return nil unless connect_result.success?

    state = android_music_volume_state
    range = state[:max] - state[:min]
    return 0 if range <= 0

    (((state[:current] - state[:min]) / range.to_f) * 100).round.clamp(0, 100)
  rescue StandardError
    nil
  end

  def set_volume_percent(percent)
    return Result.new(success?: false, message: "Controle de volume disponivel apenas para TV Android/Fire Stick via ADB.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    percent_value = percent.to_i.clamp(0, 100)
    connect_result = connect_adb
    return connect_result unless connect_result.success?

    state = android_music_volume_state
    volume_index = (state[:min] + ((state[:max] - state[:min]) * (percent_value / 100.0))).round
    current_index = state[:current]
    step_count = (volume_index - current_index).abs
    return Result.new(success?: true, message: "Volume de #{@broadcast.name} ja estava em #{percent_value}%.") if step_count.zero?

    keycode = volume_index > current_index ? "KEYCODE_VOLUME_UP" : "KEYCODE_VOLUME_DOWN"
    step_count.times do
      executed = run_adb("-s", adb_device_id, "shell", "input", "keyevent", keycode)
      return Result.new(success?: false, message: executed[:message]) unless executed[:success]
    end

    @broadcast.update_columns(tv_volume_percent: percent_value, tv_volume_synced_at: Time.current) if @broadcast.persisted?

    Result.new(success?: true, message: "Volume de #{@broadcast.name} definido para #{percent_value}%.")
  rescue StandardError => e
    Result.new(success?: false, message: e.message)
  end

  def configure_android_tv_defaults
    return Result.new(success?: false, message: "Configuracao automatica disponivel apenas para TV Android/Fire Stick.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    connect_result = connect_adb
    return connect_result unless connect_result.success?

    command = run_adb(
      "-s", adb_device_id,
      "shell", "settings", "put", "global", "stay_on_while_plugged_in", "3"
    )

    if command[:success]
      Result.new(success?: true, message: "Configuracao ADB aplicada em #{@broadcast.name}: stay_on_while_plugged_in=3.")
    else
      Result.new(success?: false, message: command[:message])
    end
  end

  def request_adb_authorization
    return Result.new(success?: false, message: "Autorizacao ADB disponivel apenas para TV Android/Fire Stick.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    connect_result = connect_adb
    state_result = run_adb("-s", adb_device_id, "get-state")
    state_message = [connect_result.message, state_result[:message]].compact.join(" ").strip

    if adb_unauthorized_message?(state_message)
      return Result.new(
        success?: true,
        message: "Pedido de autorizacao ADB enviado para #{@broadcast.name}. Confirme na TV a opcao Permitir depuracao USB e marque sempre permitir."
      )
    end

    if state_result[:success] && state_result[:message].to_s.strip.casecmp("device").zero?
      return Result.new(success?: true, message: "ADB ja autorizado em #{@broadcast.name}.")
    end

    return connect_result if connect_result.success?

    Result.new(success?: false, message: state_message.presence || connect_result.message)
  end

  def set_official_app_as_launcher
    return Result.new(success?: false, message: "Configuracao de launcher disponivel apenas para TV Android/Fire Stick.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    connect_result = connect_adb
    return connect_result unless connect_result.success?

    installed_result = official_app_installed?
    return installed_result unless installed_result.success?

    prepare_launcher = prepare_current_launcher_for_replacement
    return prepare_launcher unless prepare_launcher.success?

    run_adb("-s", adb_device_id, "shell", "input", "keyevent", "HOME")

    set_home = run_adb(
      "-s", adb_device_id,
      "shell", "cmd", "package", "set-home-activity",
      "#{OFFICIAL_APP_PACKAGE}/#{OFFICIAL_APP_MAIN_ACTIVITY}"
    )
    unless set_home[:success]
      if fire_tv?
        open_fire_tv_launcher_settings
        return Result.new(
          success?: false,
          message: fire_tv_launcher_help_message
        )
      end

      return Result.new(success?: false, message: "#{default_launcher_label} desativado, mas nao foi possivel definir Aponti TV como inicio: #{set_home[:message]}")
    end

    if fire_tv? && !fire_tv_home_resolves_to_official_app?
      home_guard = enable_fire_tv_home_guard
      run_adb("-s", adb_device_id, "shell", "input", "keyevent", "HOME")

      if home_guard.success?
        return Result.new(
          success?: true,
          message: "Fire Stick mantem o launcher Amazon como HOME do sistema, mas o Aponti TV Launcher foi ativado. Ao apertar HOME, o Aponti TV volta automaticamente para frente em #{@broadcast.name}."
        )
      end

      open_fire_tv_launcher_settings
      return home_guard
    end

    run_adb("-s", adb_device_id, "shell", "input", "keyevent", "HOME")

    Result.new(success?: true, message: "Aponti TV definido como launcher principal em #{@broadcast.name}.")
  end

  def remove_official_app_as_launcher
    return Result.new(success?: false, message: "Remocao de launcher disponivel apenas para TV Android/Fire Stick.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    connect_result = connect_adb
    return connect_result unless connect_result.success?

    enable_launcher = enable_default_launcher
    return enable_launcher unless enable_launcher.success?

    run_adb("-s", adb_device_id, "shell", "cmd", "package", "clear-preferred-activities", OFFICIAL_APP_PACKAGE)

    set_home = run_adb(
      "-s", adb_device_id,
      "shell", "cmd", "package", "set-home-activity",
      default_home_activity
    )
    if !set_home[:success]
      run_adb("-s", adb_device_id, "shell", "input", "keyevent", "HOME")
      if fire_tv?
        return Result.new(
          success?: true,
          message: "Launcher Amazon Fire TV reativado em #{@broadcast.name}. Se o Fire OS pedir escolha de inicio, selecione o launcher padrao."
        )
      end

      return Result.new(success?: false, message: "Launcher padrao reativado, mas nao foi possivel definir como inicio: #{set_home[:message]}")
    end

    run_adb("-s", adb_device_id, "shell", "input", "keyevent", "HOME")

    Result.new(success?: true, message: "Launcher padrao restaurado em #{@broadcast.name}.")
  end

  def open_official_app
    return Result.new(success?: false, message: "Abrir app esta disponivel apenas para TV Android/Fire Stick via ADB.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    connect_result = connect_adb
    return connect_result unless connect_result.success?

    installed_result = official_app_installed?
    return installed_result unless installed_result.success?

    guard_result = enable_home_guard
    return guard_result unless guard_result.success?

    open_result = run_adb(
        "-s", adb_device_id,
        "shell", "am", "start",
        "-n", "#{OFFICIAL_APP_PACKAGE}/#{OFFICIAL_APP_MAIN_ACTIVITY}",
        "--ez", "force_selection_mode", "false",
        "--el", "broadcast_id", @broadcast.id.to_s
      )

    if open_result[:success]
      Result.new(success?: true, message: "Aponti TV aberto em #{@broadcast.name}.")
    else
      Result.new(success?: false, message: "Nao foi possivel abrir o Aponti TV: #{open_result[:message]}")
    end
  end

  def install_official_app(apk_path)
    return Result.new(success?: false, message: "Atualizacao disponivel apenas para TV Android/Fire Stick via ADB.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?
    return Result.new(success?: false, message: "APK do Aponti TV nao encontrado. Gere a build antes de atualizar.") unless File.file?(apk_path)

    connect_result = connect_adb
    return connect_result unless connect_result.success?

    install_result = run_adb_with_timeout(180, "-s", adb_device_id, "install", "-r", apk_path.to_s)
    unless install_result[:success] && install_result[:message].to_s.match?(/Success/i)
      return Result.new(success?: false, message: "Nao foi possivel atualizar o Aponti TV: #{install_result[:message]}")
    end

    run_adb("-s", adb_device_id, "shell", "am", "force-stop", OFFICIAL_APP_PACKAGE)
    open_result = run_adb(
      "-s", adb_device_id, "shell", "am", "start",
      "-n", "#{OFFICIAL_APP_PACKAGE}/#{OFFICIAL_APP_MAIN_ACTIVITY}",
      "--ez", "force_selection_mode", "false",
      "--el", "broadcast_id", @broadcast.id.to_s
    )
    return Result.new(success?: false, message: "APK instalado, mas o app nao abriu: #{open_result[:message]}") unless open_result[:success]

    Result.new(success?: true, message: "Aponti TV atualizado para a versao #{Broadcast::OFFICIAL_APP_CURRENT_VERSION_NAME} em #{@broadcast.name}.")
  rescue StandardError => e
    Result.new(success?: false, message: e.message)
  end

  def control_mode_label
    if adb_device?
      "ADB via #{target_host.presence || 'IP da TV'}:#{adb_port} para ligar e desligar"
    else
      "Wake-on-LAN para ligar"
    end
  end

  private

  def adb_device?
    @broadcast.respond_to?(:adb_device?) ? @broadcast.adb_device? : @broadcast.tv_device_type_android_tv?
  end

  def fire_tv?
    @broadcast.respond_to?(:tv_device_type_fire_tv?) && @broadcast.tv_device_type_fire_tv?
  end

  def default_launcher_package
    fire_tv? ? FIRE_TV_LAUNCHER_PACKAGE : GOOGLE_TV_LAUNCHER_PACKAGE
  end

  def default_launcher_label
    fire_tv? ? "launcher Amazon Fire TV" : "launcher Google TV"
  end

  def default_home_activity
    if fire_tv?
      "#{FIRE_TV_LAUNCHER_PACKAGE}/#{FIRE_TV_HOME_ACTIVITY}"
    else
      "#{GOOGLE_TV_LAUNCHER_PACKAGE}/#{GOOGLE_TV_HOME_ACTIVITY}"
    end
  end

  def prepare_current_launcher_for_replacement
    return Result.new(success?: true, message: "Fire Stick mantem o launcher Amazon protegido; pulando desativacao do pacote.") if fire_tv?

    disable_current_launcher
  end

  def disable_current_launcher
    disable_launcher = run_adb(
      "-s", adb_device_id,
      "shell", "pm", "disable-user", "--user", "0", default_launcher_package
    )

    return Result.new(success?: true, message: "#{default_launcher_label} desativado.") if disable_launcher[:success]

    Result.new(
      success?: false,
      message: "App encontrado, mas nao foi possivel desativar #{default_launcher_label}: #{disable_launcher[:message]}"
    )
  end

  def enable_default_launcher
    enable_launcher = run_adb(
      "-s", adb_device_id,
      "shell", "pm", "enable", default_launcher_package
    )

    return Result.new(success?: true, message: "#{default_launcher_label} reativado.") if enable_launcher[:success]

    Result.new(
      success?: false,
      message: "Nao foi possivel reativar #{default_launcher_label}: #{enable_launcher[:message]}"
    )
  end

  def fire_tv_home_resolves_to_official_app?
    result = run_adb(
      "-s", adb_device_id,
      "shell", "cmd", "package", "resolve-activity", "--brief",
      "-a", "android.intent.action.MAIN",
      "-c", "android.intent.category.HOME"
    )

    result[:success] && result[:message].to_s.include?(OFFICIAL_APP_PACKAGE)
  end

  def enable_home_guard
    service = "#{OFFICIAL_APP_PACKAGE}/.ApontiHomeGuardAccessibilityService"
    service_result = run_adb("-s", adb_device_id, "shell", "settings", "put", "secure", "enabled_accessibility_services", service)
    return Result.new(success?: false, message: "Nao foi possivel ativar o guard do Aponti TV: #{service_result[:message]}") unless service_result[:success]

    enabled_result = run_adb("-s", adb_device_id, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
    return Result.new(success?: false, message: "Nao foi possivel habilitar acessibilidade na TV: #{enabled_result[:message]}") unless enabled_result[:success]

    enabled_service = run_adb("-s", adb_device_id, "shell", "settings", "get", "secure", "enabled_accessibility_services")
    accessibility_enabled = run_adb("-s", adb_device_id, "shell", "settings", "get", "secure", "accessibility_enabled")

    if enabled_service[:message].to_s.include?("ApontiHomeGuardAccessibilityService") && accessibility_enabled[:message].to_s.strip == "1"
      Result.new(success?: true, message: "Guard do Aponti TV ativado em #{@broadcast.name}.")
    else
      Result.new(success?: false, message: fire_tv_launcher_help_message)
    end
  end

  alias enable_fire_tv_home_guard enable_home_guard

  def fire_tv_launcher_help_message
    "O Fire OS manteve o launcher Amazon como principal mesmo apos salvar o Aponti TV como HOME. Abri a tela de Acessibilidade na TV: ative uma vez a opcao 'Aponti TV Launcher'. Depois disso, ao apertar HOME, o Aponti TV volta automaticamente para frente."
  end

  def open_fire_tv_launcher_settings
    run_adb(
      "-s", adb_device_id,
      "shell", "am", "start",
      "-a", "android.settings.ACCESSIBILITY_SETTINGS"
    )
  end

  def ping_online?
    ping_command = if Gem.win_platform?
      ["ping", "-n", "1", "-w", "1000", @broadcast.tv_ip]
    else
      ["ping", "-c", "1", "-W", "1", @broadcast.tv_ip]
    end

    system(*ping_command, out: File::NULL, err: File::NULL)
  end

  def adb_port_open?
    return false unless adb_device?

    Socket.tcp(target_host, adb_port, connect_timeout: 1) do |socket|
      socket.close
      return true
    end
  rescue StandardError
    false
  end

  def adb_online?
    return false unless adb_device?
    return false unless adb_port_open?

    connect_result = connect_adb
    return false unless connect_result.success?

    state_result = run_adb("-s", adb_device_id, "get-state")
    return false unless state_result[:success]

    state_result[:message].to_s.strip.casecmp("device").zero?
  end

  def android_power_state
    return :unknown unless adb_device?
    return :unreachable unless adb_port_open?

    connect_result = connect_adb
    return :on if adb_unauthorized_message?(connect_result.message)
    return :unreachable unless connect_result.success?

    power_result = run_adb("-s", adb_device_id, "shell", "dumpsys", "power")
    return :on if adb_unauthorized_message?(power_result[:message])
    return :unknown unless power_result[:success]

    power_output = power_result[:message].to_s

    return :on if power_output.match?(/Display Power:\s*state=ON/i)
    return :off if power_output.match?(/Display Power:\s*state=OFF/i)
    return :on if power_output.match?(/mHoldingDisplaySuspendBlocker=true/i)
    return :off if power_output.match?(/mHoldingDisplaySuspendBlocker=false/i)
    return :on if power_output.match?(/mInteractive=true/i)
    return :off if power_output.match?(/mInteractive=false/i)
    return :on if power_output.match?(/mWakefulness=Awake/i)
    return :off if power_output.match?(/mWakefulness=Asleep/i)

    :unknown
  end

  def adb_unauthorized_message?(message)
    message.to_s.match?(/unauthorized|confirmation dialog|ADB_VENDOR_KEYS/i)
  end

  def adb_port
    @broadcast.adb_port.presence || DEFAULT_ADB_PORT
  end

  def adb_device_id
    "#{target_host}:#{adb_port}"
  end

  def target_host
    @broadcast.adb_target_host.to_s.strip.presence
  end

  def official_app_installed?
    package_result = run_adb("-s", adb_device_id, "shell", "pm", "path", OFFICIAL_APP_PACKAGE)
    package_path = package_result[:message].to_s.strip

    if package_result[:success] && package_path.include?("package:")
      Result.new(success?: true, message: "App #{OFFICIAL_APP_PACKAGE} encontrado na TV.")
    else
      Result.new(success?: false, message: "O app Aponti TV nao esta instalado na TV #{adb_device_id}. Instale o APK antes de definir como launcher.")
    end
  end

  def execute_android_power_off
    execute_adb_power_action_sequence(
      [
        ["shell", "input", "keyevent", "KEYCODE_SLEEP"],
        ["shell", "input", "keyevent", "26"],
        ["shell", "input", "keyevent", "KEYCODE_POWER"]
      ],
      success_message: "Comando de desligar enviado para #{@broadcast.name}.",
      expected_online: false
    )
  end

  def execute_android_volume_key(keycode, success_message)
    return Result.new(success?: false, message: "Controle de volume disponivel apenas para TV Android/Fire Stick via ADB.") unless adb_device?
    return Result.new(success?: false, message: "Informe o IP da TV ou o IP ADB/VPN.") if target_host.blank?

    adb_command("shell", "input", "keyevent", keycode).then do |result|
      result.success? ? Result.new(success?: true, message: success_message) : result
    end
  rescue StandardError => e
    Result.new(success?: false, message: e.message)
  end

  def android_music_volume_state
    result = run_adb("-s", adb_device_id, "shell", "cmd", "media_session", "volume", "--stream", "3", "--get")
    output = result[:message].to_s

    if result[:success] && output.match(/volume is\s+(\d+)\s+in range\s*\[(\d+)\.\.(\d+)\]/i)
      { current: Regexp.last_match(1).to_i, min: Regexp.last_match(2).to_i, max: Regexp.last_match(3).to_i }
    else
      { current: 0, min: 0, max: 100 }
    end
  end

  def execute_adb_power_action(command, success_message:, expected_online: nil)
    connect_result = connect_adb
    return connect_result unless connect_result.success?

    executed = run_adb("-s", adb_device_id, *command)
    return Result.new(success?: false, message: executed[:message]) unless executed[:success]

    return Result.new(success?: true, message: success_message) if expected_online.nil?

    verify_power_state(
      expected_online: expected_online,
      success_message: success_message,
      failure_message: expected_online ? "A TV nao respondeu como ligada apos o comando." : "A TV ainda respondeu via ADB apos o comando de desligar. Verifique se ela aceita desligamento remoto por ADB."
    )
  end

  def adb_command(*command)
    connect_result = connect_adb
    return connect_result unless connect_result.success?

    executed = run_adb("-s", adb_device_id, *command)

    if executed[:success]
      Result.new(success?: true, message: "Comando enviado para #{@broadcast.name}.")
    else
      Result.new(success?: false, message: executed[:message])
    end
  end

  def connect_adb
    result = run_adb("connect", adb_device_id)
    return Result.new(success?: true, message: result[:message]) if result[:success]

    Result.new(success?: false, message: result[:message])
  end

  def run_adb(*args)
    run_adb_with_timeout(3, *args)
  end

  def run_adb_with_timeout(timeout_seconds, *args)
    executable = adb_executable
    return { success: false, message: "ADB nao encontrado no Windows. Instale o platform-tools ou ajuste o PATH." } if executable.blank?

    output, status = Timeout.timeout(timeout_seconds) { Open3.capture2e(executable, *args) }
    output_text = output.to_s.strip

    success = status.success? || output_text.match?(/already connected|connected to/i)
    message = if success
      output_text.presence || "Comando ADB executado."
    else
      output_text.presence || "Falha ao executar comando ADB."
    end

    { success: success, message: message }
  rescue Errno::ENOENT
    { success: false, message: "ADB nao encontrado no Windows. Instale o platform-tools ou ajuste o PATH." }
  rescue Timeout::Error
    { success: false, message: "ADB nao respondeu em #{timeout_seconds} segundos." }
  rescue StandardError => e
    { success: false, message: e.message }
  end

  def adb_executable
    @adb_executable ||= ADB_CANDIDATE_PATHS.find do |path|
      next false if path.blank?

      if path.include?("/") || path.include?("\\")
        File.exist?(File.expand_path(path))
      else
        system("where", path, out: File::NULL, err: File::NULL)
      end
    end
  end

  def send_wol_packet
    mac = @broadcast.tv_mac_address.to_s.delete(":-").downcase
    raise "MAC invalido." unless mac.match?(/\A[0-9a-f]{12}\z/)

    data = ["FF" * 6 + mac * 16].pack("H*")
    socket = UDPSocket.new
    socket.setsockopt(Socket::SOL_SOCKET, Socket::SO_BROADCAST, true)
    socket.send(data, 0, "255.255.255.255", 9)
  ensure
    socket&.close
  end

  def verify_power_state(expected_online:, success_message:, failure_message:)
    sleep STATUS_WAIT_SECONDS

    current_state = if adb_device?
      android_power_state
    else
      online? ? :on : :off
    end

    expected_state = expected_online ? :on : :off
    return Result.new(success?: true, message: success_message) if current_state == expected_state

    Result.new(success?: false, message: failure_message)
  end

  def execute_adb_power_action_sequence(commands, success_message:, expected_online:)
    connect_result = connect_adb
    return connect_result unless connect_result.success?

    commands.each do |command|
      executed = run_adb("-s", adb_device_id, *command)
      next unless executed[:success]

      sleep POWER_COMMAND_DELAY_SECONDS

      current_state = android_power_state
      expected_state = expected_online ? :on : :off
      return Result.new(success?: true, message: success_message) if current_state == expected_state
    end

    verify_power_state(
      expected_online: expected_online,
      success_message: success_message,
      failure_message: expected_online ? "A TV nao respondeu como ligada apos o comando." : "A TV ainda respondeu como ligada via ADB apos tentar desligar."
    )
  end
end
