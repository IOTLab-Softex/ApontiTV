class AddAppForegroundReopenTrackingToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :last_app_foreground_reopen_at, :datetime
    add_column :broadcasts, :last_app_foreground_reopen_message, :string
  end
end
