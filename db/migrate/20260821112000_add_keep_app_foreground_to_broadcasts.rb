class AddKeepAppForegroundToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :keep_app_foreground_enabled, :boolean, default: false, null: false
  end
end
