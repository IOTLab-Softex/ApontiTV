class AddGoogleOauthToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :google_oauth_enabled, :boolean, default: false, null: false
    add_column :streaming_configurations, :google_client_id, :string
    add_column :streaming_configurations, :google_client_secret, :text
  end
end
