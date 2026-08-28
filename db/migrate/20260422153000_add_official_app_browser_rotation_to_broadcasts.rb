class AddOfficialAppBrowserRotationToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :official_app_page_url, :string
    add_column :broadcasts, :official_app_switch_interval_seconds, :integer
    add_column :broadcasts, :official_app_page_duration_seconds, :integer
    add_column :broadcasts, :official_app_transition_style, :string
    add_column :broadcasts, :official_app_transition_duration_ms, :integer
  end
end
