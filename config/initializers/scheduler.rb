require 'rufus-scheduler'

return unless Rails.env.development? || Rails.env.production?
argv = Array(ARGV)
return if ENV["SKIP_BROADCAST_STATUS_CHECK"] == "1" || argv.any? { |arg| arg.start_with?("db:") }
return if defined?($softex_tv_scheduler_started) && $softex_tv_scheduler_started

scheduler = Rufus::Scheduler.new
$softex_tv_scheduler_started = true
$softex_tv_scheduler = scheduler

scheduler.cron '* * * * *' do
  Rails.logger.info "[Scheduler] Verificando agendamentos..."
  ScheduleUpdater.run
  TvPowerScheduleRunner.run
end

scheduler.every '15s' do
  TvAppForegroundMonitor.run
end
