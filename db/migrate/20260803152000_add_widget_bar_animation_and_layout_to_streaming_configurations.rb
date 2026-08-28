class AddWidgetBarAnimationAndLayoutToStreamingConfigurations < ActiveRecord::Migration[7.0]
  def change
    add_column :streaming_configurations, :widget_bar_animation, :string, default: "slide", null: false
    add_column :streaming_configurations, :widget_bar_layout_mode, :string, default: "overlay", null: false
  end
end
