class AddAppDeviceTokenToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :app_device_token, :string
    add_column :broadcasts, :app_device_registered_at, :datetime
    add_column :broadcasts, :app_device_last_seen_at, :datetime

    add_index :broadcasts, :app_device_token, unique: true
  end
end
