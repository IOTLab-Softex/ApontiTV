class AddForecastDisplaySettings < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_forecast_card_animation, :string, default: "stagger_up", null: false
    add_column :streaming_configurations, :widget_forecast_display_mode, :string, default: "always", null: false
    add_column :streaming_configurations, :widget_forecast_display_minutes, :integer, default: 5, null: false
  end
end
