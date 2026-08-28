class AddVolumeSyncToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :tv_volume_percent, :integer, default: 50, null: false
    add_column :broadcasts, :tv_volume_synced_at, :datetime
  end
end
