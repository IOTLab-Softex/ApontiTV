class AddWidgetBarWeatherTestConditionToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_bar_weather_test_condition, :string, default: "real", null: false
  end
end
