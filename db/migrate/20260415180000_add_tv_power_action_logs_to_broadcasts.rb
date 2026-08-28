class AddTvPowerActionLogsToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :last_tv_power_action, :string
    add_column :broadcasts, :last_tv_power_action_status, :string
    add_column :broadcasts, :last_tv_power_action_message, :text
    add_column :broadcasts, :last_tv_power_action_at, :datetime
  end
end
