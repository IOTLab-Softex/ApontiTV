class AddPlaylistSyncMonitoringToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :current_player_position_ms, :integer, default: 0, null: false
    add_column :broadcasts, :playlist_resync_requested_at, :datetime
    add_column :broadcasts, :playlist_resync_reason, :string
  end
end
