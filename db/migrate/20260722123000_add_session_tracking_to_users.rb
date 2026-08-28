class AddSessionTrackingToUsers < ActiveRecord::Migration[7.0]
  def change
    add_column :users, :last_accessed_at, :datetime
    add_column :users, :last_signed_out_at, :datetime

    add_index :users, :last_accessed_at
    add_index :users, :last_signed_out_at
  end
end
