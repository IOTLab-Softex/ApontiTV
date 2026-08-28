class AddOfficialAppDurationUnitsToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :official_app_switch_interval_unit, :string
    add_column :broadcasts, :official_app_page_duration_unit, :string
  end
end
