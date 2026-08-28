class BroadcastStatusChecker
  FFMPEG_CANDIDATE_PATHS = [
    "C:/nginx/ffmpeg/bin/ffmpeg.exe",
    "C:/nginx/ffmpeg/bin/ffmpeg",
    "ffmpeg"
  ].freeze

  def self.verify_all
    Broadcast.where(status: "running").find_each do |broadcast|
      if !broadcast.uses_ffmpeg_streaming?
        broadcast.update_columns(process_pid: nil) if broadcast.process_pid.present?
        next
      end
      next if broadcast.command.blank?
      next unless broadcast.process_pid.nil? || !process_alive?(broadcast.process_pid)

      Rails.logger.info "Broadcast #{broadcast.id} estava como 'running', mas o processo nao existe. Reiniciando..."

      begin
        pid = spawn_hidden(broadcast.command)
      rescue Errno::ENOENT => e
        Rails.logger.warn "Nao foi possivel reiniciar o broadcast #{broadcast.id}: #{e.message}"
        broadcast.update(process_pid: nil, status: "stopped")
        next
      end

      Process.detach(pid)
      broadcast.update(process_pid: pid, status: "running")

      Rails.logger.info "Broadcast #{broadcast.id} reiniciado com novo PID #{pid}"
    end
  end

  def self.process_alive?(pid)
    pid = pid.to_i
    if Gem.win_platform?
      output = `#{windows_tasklist_path} /FI "PID eq #{pid}"`
      !output.include?("Nenhum processo") && output.include?(pid.to_s)
    else
      Process.getpgid(pid)
      true
    end
  rescue Errno::ESRCH, NotImplementedError
    false
  end

  def self.spawn_hidden(command)
    if Gem.win_platform?
      Process.spawn(windows_shell_path, "/c", command_with_ffmpeg_path(command), out: "NUL", err: "NUL")
    else
      Process.spawn(command, out: "/dev/null", err: "/dev/null")
    end
  end

  def self.command_with_ffmpeg_path(command)
    ffmpeg_path = ffmpeg_executable
    return command if ffmpeg_path.nil? || ffmpeg_path.empty?

    command.sub(/\Affmpeg\b/, "\"#{ffmpeg_path}\"")
  end

  def self.ffmpeg_executable
    @ffmpeg_executable ||= FFMPEG_CANDIDATE_PATHS.find do |path|
      if path.include?("/") || path.include?("\\")
        File.exist?(File.expand_path(path))
      else
        system("where", path, out: File::NULL, err: File::NULL)
      end
    end
  end

  def self.windows_shell_path
    @windows_shell_path ||= begin
      candidates = [
        ENV["COMSPEC"],
        File.join(ENV.fetch("WINDIR", "C:/Windows"), "System32", "cmd.exe"),
        "C:/Windows/System32/cmd.exe"
      ].compact

      candidates.find { |path| File.exist?(File.expand_path(path)) } || "cmd.exe"
    end
  end

  def self.windows_tasklist_path
    @windows_tasklist_path ||= begin
      candidates = [
        File.join(ENV.fetch("WINDIR", "C:/Windows"), "System32", "tasklist.exe"),
        "C:/Windows/System32/tasklist.exe"
      ]

      candidates.find { |path| File.exist?(File.expand_path(path)) } || "tasklist"
    end
  end
end
