class AddPairingToDesktopAgents < ActiveRecord::Migration[7.0]
  def change
    change_column_null :desktop_agents, :token_digest, true
    add_column :desktop_agents, :pairing_secret_digest, :string
    add_column :desktop_agents, :approval_code, :string
    add_column :desktop_agents, :status, :string, default: "approved", null: false
    add_column :desktop_agents, :approved_at, :datetime
    add_column :desktop_agents, :paired_at, :datetime

    add_index :desktop_agents, :pairing_secret_digest
    add_index :desktop_agents, :approval_code
    add_index :desktop_agents, :status
  end
end
