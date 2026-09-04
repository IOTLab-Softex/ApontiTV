class AddOfficialAppWebOnlyToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :official_app_web_only, :boolean, default: false, null: false
  end
end
