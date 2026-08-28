class AddWidgetBarSettingsToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widgets_ffmpeg_enabled, :boolean, default: true, null: false
    add_column :streaming_configurations, :widgets_official_app_enabled, :boolean, default: true, null: false
    add_column :streaming_configurations, :widget_bar_style, :string, default: "transparent_blur", null: false
    add_column :streaming_configurations, :widget_bar_color, :string, default: "#0b1020", null: false
    add_column :streaming_configurations, :widget_bar_opacity, :integer, default: 72, null: false
    add_column :streaming_configurations, :widget_bar_blur_enabled, :boolean, default: true, null: false
    add_column :streaming_configurations, :widget_bar_behavior, :string, default: "fixed", null: false
    add_column :streaming_configurations, :widget_bar_show_seconds, :integer, default: 8, null: false
    add_column :streaming_configurations, :widget_bar_content_mode, :string, default: "time_weather", null: false
  end
end
