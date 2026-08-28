class AddWidgetBarEdgeSpacingEnabledToBroadcasts < ActiveRecord::Migration[7.0]
  def change
    add_column :broadcasts, :widget_bar_edge_spacing_enabled, :boolean, default: true, null: false
  end
end
