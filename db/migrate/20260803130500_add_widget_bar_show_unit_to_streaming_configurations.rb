class AddWidgetBarShowUnitToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_bar_show_unit, :string, default: "seconds", null: false
  end
end
