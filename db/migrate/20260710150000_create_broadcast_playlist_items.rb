class CreateBroadcastPlaylistItems < ActiveRecord::Migration[7.0]
  def change
    create_table :broadcast_playlist_items do |t|
      t.references :broadcast, null: false, foreign_key: true
      t.string :media_type, null: false, default: "video"
      t.integer :position, null: false, default: 0
      t.integer :duration_seconds, null: false, default: 10
      t.timestamps
    end

    add_index :broadcast_playlist_items, [:broadcast_id, :position], name: "index_playlist_items_on_broadcast_and_position"
  end
end
