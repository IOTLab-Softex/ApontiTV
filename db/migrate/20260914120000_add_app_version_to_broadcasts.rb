class AddAppVersionToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :app_version_name, :string
    add_column :broadcasts, :app_version_code, :integer
    add_column :broadcasts, :app_version_reported_at, :datetime
  end
end
