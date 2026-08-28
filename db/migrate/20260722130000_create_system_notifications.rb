class CreateSystemNotifications < ActiveRecord::Migration[7.0]
  def change
    create_table :system_notifications do |t|
      t.string :title, null: false
      t.text :message
      t.string :severity, null: false, default: "info"
      t.string :event_key, null: false
      t.string :source_type
      t.integer :source_id
      t.text :metadata
      t.datetime :read_at

      t.timestamps
    end

    add_index :system_notifications, :created_at
    add_index :system_notifications, :read_at
    add_index :system_notifications, :severity
    add_index :system_notifications, [:source_type, :source_id]

    add_column :broadcasts, :last_known_tv_power_status, :string
    add_column :broadcasts, :last_known_tv_power_status_at, :datetime
  end
end
