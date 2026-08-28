class AddOfficialAppRotationTriggerToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :official_app_rotation_trigger, :string
  end
end
