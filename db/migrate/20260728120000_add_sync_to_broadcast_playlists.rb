class AddSyncToBroadcastPlaylists < ActiveRecord::Migration[7.0]
  def change
    add_column :saved_broadcast_playlists, :sync_enabled, :boolean, null: false, default: false
    add_column :broadcasts, :saved_broadcast_playlist_id, :integer
    add_column :broadcasts, :playlist_sync_enabled, :boolean, null: false, default: false
    add_column :broadcasts, :playlist_sync_started_at, :datetime

    add_index :broadcasts, :saved_broadcast_playlist_id
    add_foreign_key :broadcasts, :saved_broadcast_playlists
  end
end
