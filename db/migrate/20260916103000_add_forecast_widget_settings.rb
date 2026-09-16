class AddForecastWidgetSettings < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_forecast_enabled, :boolean, default: true, null: false
    add_column :streaming_configurations, :widget_forecast_days, :integer, default: 5, null: false
    add_column :streaming_configurations, :widget_forecast_latitude, :decimal, precision: 10, scale: 6, default: -8.047600, null: false
    add_column :streaming_configurations, :widget_forecast_longitude, :decimal, precision: 10, scale: 6, default: -34.877000, null: false
    add_column :streaming_configurations, :widget_forecast_timezone, :string, default: "America/Sao_Paulo", null: false

    add_column :broadcasts, :widget_forecast_enabled, :boolean, default: true, null: false
  end
end
