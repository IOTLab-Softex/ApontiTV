argv = Array(ARGV)

unless ENV["SKIP_BROADCAST_STATUS_CHECK"] == "1" || argv.any? { |arg| arg.start_with?("db:") } || (defined?($softex_tv_broadcast_status_hook_registered) && $softex_tv_broadcast_status_hook_registered)
  $softex_tv_broadcast_status_hook_registered = true

  Rails.application.config.after_initialize do
    unless ActiveRecord::Base.connection.data_source_exists?("broadcasts")
      Rails.logger.warn "Verificacao de broadcasts ignorada: tabela broadcasts ainda nao existe."
      next
    end

    Rails.logger.info "Verificando broadcasts orfaos apos inicializacao..."
    BroadcastStatusChecker.verify_all
  rescue ActiveRecord::ActiveRecordError => e
    Rails.logger.warn "Verificacao de broadcasts ignorada: #{e.message}"
  end
end
