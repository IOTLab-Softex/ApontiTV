class AddCurrentPlayerPlaylistItemToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :current_player_playlist_item_id, :integer
    add_column :broadcasts, :current_player_playlist_item_updated_at, :datetime
    add_index :broadcasts, :current_player_playlist_item_id
  end
end
