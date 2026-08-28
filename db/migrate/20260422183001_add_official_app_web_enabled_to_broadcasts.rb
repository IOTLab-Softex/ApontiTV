class AddOfficialAppWebEnabledToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :official_app_web_enabled, :boolean, default: false, null: false
  end
end