class CreateVideoEditorProjects < ActiveRecord::Migration[7.0]
  def change
    create_table :video_editor_projects do |t|
      t.string  :title,       null: false, default: "Sem titulo"
      t.string  :orientation, null: false, default: "landscape"
      t.text    :project_data
      t.integer :rendered_blob_id
      t.references :user, null: false, foreign_key: true
      t.timestamps
    end
  end
end
