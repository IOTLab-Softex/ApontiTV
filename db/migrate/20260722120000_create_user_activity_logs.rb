class CreateUserActivityLogs < ActiveRecord::Migration[7.0]
  def change
    create_table :user_activity_logs do |t|
      t.references :user, null: true, foreign_key: true
      t.string :user_email, null: false
      t.string :action_key, null: false
      t.string :action_label, null: false
      t.string :controller_name, null: false
      t.string :action_name, null: false
      t.string :request_method, null: false
      t.string :path, null: false
      t.string :ip_address
      t.string :user_agent
      t.integer :status
      t.string :record_type
      t.string :record_id
      t.text :request_params
      t.datetime :occurred_at, null: false

      t.timestamps
    end

    add_index :user_activity_logs, :occurred_at
    add_index :user_activity_logs, :action_key
    add_index :user_activity_logs, [:controller_name, :action_name]
  end
end
