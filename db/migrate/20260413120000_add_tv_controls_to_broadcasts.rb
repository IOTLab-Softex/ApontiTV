class AddTvControlsToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :playback_app_type, :string, default: "official_app", null: false
    add_column :broadcasts, :tv_device_type, :string, default: "normal_tv", null: false
    add_column :broadcasts, :tv_ip, :string
    add_column :broadcasts, :tv_mac_address, :string
    add_column :broadcasts, :adb_port, :integer, default: 5555, null: false
  end
end
