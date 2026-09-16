class AddAndar360OfflineCacheToUsers < ActiveRecord::Migration[7.0]
  def change
    add_column :users, :andar360_last_verified_at, :datetime
    add_column :users, :andar360_session_version, :string
    add_column :users, :andar360_force_password_change, :boolean, default: false, null: false
    add_column :users, :andar360_login, :string
    add_index :users, :andar360_login
  end
end
