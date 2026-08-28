class AddAppPlayerPresenceToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :app_player_presence_status, :string, null: false, default: "offline"
    add_column :broadcasts, :app_player_presence_updated_at, :datetime
  end
end
