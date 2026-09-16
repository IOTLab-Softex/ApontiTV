class AddScreenPowerStatusToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :app_screen_on, :boolean
    add_column :broadcasts, :app_screen_status_updated_at, :datetime
  end
end
