class AddAdbHostToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :adb_host, :string
  end
end
