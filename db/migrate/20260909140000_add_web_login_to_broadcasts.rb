class AddWebLoginToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :official_app_login_enabled, :boolean, default: false, null: false
    add_column :broadcasts, :official_app_login_username, :string
    add_column :broadcasts, :official_app_login_password_ciphertext, :text
  end
end
