class CreateSavedBroadcastPlaylists < ActiveRecord::Migration[7.0]
  def change
    create_table :saved_broadcast_playlists do |t|
      t.string :name, null: false
      t.integer :image_duration_seconds, null: false, default: 10
      t.string :transition_style, null: false, default: "fade"
      t.integer :transition_duration_ms, null: false, default: 600

      t.timestamps
    end

    create_table :saved_broadcast_playlist_items do |t|
      t.references :saved_broadcast_playlist, null: false, foreign_key: true, index: { name: "index_saved_playlist_items_on_playlist_id" }
      t.references :media_blob, null: false, foreign_key: { to_table: :active_storage_blobs }, index: { name: "index_saved_playlist_items_on_media_blob_id" }
      t.string :media_type, null: false, default: "video"
      t.integer :position, null: false, default: 0
      t.integer :duration_seconds, null: false, default: 10
      t.string :transition_style, null: false, default: "fade"
      t.integer :transition_duration_ms, null: false, default: 600

      t.timestamps
    end

    add_index :saved_broadcast_playlist_items,
              [:saved_broadcast_playlist_id, :position],
              name: "index_saved_playlist_items_on_playlist_and_position"

    add_column :broadcast_playlist_items, :transition_style, :string, null: false, default: "fade"
    add_column :broadcast_playlist_items, :transition_duration_ms, :integer, null: false, default: 600
  end
end
