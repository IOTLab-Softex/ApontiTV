class AddAndar360IdentityToUsers < ActiveRecord::Migration[7.0]
  def change
    add_column :users, :andar360_user_id, :bigint
    add_index :users, :andar360_user_id, unique: true
  end
end
