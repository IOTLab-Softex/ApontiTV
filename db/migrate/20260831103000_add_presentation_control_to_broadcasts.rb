class AddPresentationControlToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :presentation_mode_enabled, :boolean, default: false, null: false
    add_column :broadcasts, :presentation_command, :string
    add_column :broadcasts, :presentation_command_version, :integer, default: 0, null: false
    add_column :broadcasts, :presentation_command_updated_at, :datetime
    add_column :broadcasts, :presentation_paused, :boolean, default: false, null: false
  end
end
