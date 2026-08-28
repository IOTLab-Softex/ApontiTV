class AddTvPowerScheduleToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :tv_power_on_time, :string
    add_column :broadcasts, :tv_power_off_time, :string
    add_column :broadcasts, :tv_disabled_weekdays, :text
  end
end
