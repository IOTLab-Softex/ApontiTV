class CreateDesktopAgents < ActiveRecord::Migration[7.0]
  def change
    create_table :desktop_agents do |t|
      t.string :name, null: false
      t.string :token_digest, null: false
      t.string :hostname
      t.string :ip_address
      t.string :agent_version
      t.string :last_wallpaper_version
      t.datetime :last_seen_at
      t.datetime :last_wallpaper_applied_at
      t.boolean :enabled, default: true, null: false

      t.timestamps
    end

    add_index :desktop_agents, :token_digest, unique: true
    add_index :desktop_agents, :enabled
    add_index :desktop_agents, :last_seen_at
  end
end
