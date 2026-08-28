class AddTvPowerScheduleExecutionTrackingToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :last_tv_power_on_at, :datetime
    add_column :broadcasts, :last_tv_power_off_at, :datetime
  end
end
