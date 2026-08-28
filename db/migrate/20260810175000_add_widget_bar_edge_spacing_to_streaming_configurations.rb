class AddWidgetBarEdgeSpacingToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_bar_edge_spacing, :integer, null: false, default: 0
  end
end
