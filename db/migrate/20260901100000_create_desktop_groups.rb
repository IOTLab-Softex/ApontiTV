class CreateDesktopGroups < ActiveRecord::Migration[7.0]
  def change
    create_table :desktop_groups do |t|
      t.string :name, null: false
      t.timestamps
    end
    add_index :desktop_groups, :name, unique: true
    add_reference :desktop_agents, :desktop_group, foreign_key: true, index: true
  end
end
