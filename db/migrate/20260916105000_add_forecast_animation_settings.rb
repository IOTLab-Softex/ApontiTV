class AddForecastAnimationSettings < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_forecast_animation_enabled, :boolean, default: true, null: false
    add_column :streaming_configurations, :widget_forecast_travel_seconds, :integer, default: 12, null: false
    add_column :streaming_configurations, :widget_forecast_pause_seconds, :integer, default: 3, null: false
  end
end
