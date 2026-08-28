class AddWidgetBarTimingAndWeatherUrlToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_bar_appear_seconds, :integer, default: 0, null: false
    add_column :streaming_configurations, :widget_bar_appear_unit, :string, default: "seconds", null: false
    add_column :streaming_configurations, :widget_bar_hide_seconds, :integer, default: 0, null: false
    add_column :streaming_configurations, :widget_bar_hide_unit, :string, default: "seconds", null: false
    add_column :streaming_configurations, :widget_bar_weather_api_url, :string, default: "https://labs.aponti.org.br/api/current_data?station=estacao_01", null: false
  end
end
