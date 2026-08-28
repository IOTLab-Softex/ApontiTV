class AddVideoDurationModeToPlaylists < ActiveRecord::Migration[7.0]
  def change
    add_column :saved_broadcast_playlists, :video_duration_mode, :string, null: false, default: "image_duration"
    add_column :broadcast_playlist_items, :video_duration_mode, :string, null: false, default: "image_duration"
  end
end
