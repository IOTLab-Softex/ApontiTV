# This file is auto-generated from the current state of the database. Instead
# of editing this file, please use the migrations feature of Active Record to
# incrementally modify your database, and then regenerate this schema definition.
#
# This file is the source Rails uses to define your schema when running `bin/rails
# db:schema:load`. When creating a new database, `bin/rails db:schema:load` tends to
# be faster and is potentially less error prone than running all of your
# migrations from scratch. Old migrations may fail to apply correctly if those
# migrations use external dependencies or application code.
#
# It's strongly recommended that you check this file into your version control system.

ActiveRecord::Schema[7.0].define(version: 2026_09_03_150000) do
  create_table "active_storage_attachments", force: :cascade do |t|
    t.string "name", null: false
    t.string "record_type", null: false
    t.integer "record_id", null: false
    t.integer "blob_id", null: false
    t.datetime "created_at", null: false
    t.index ["blob_id"], name: "index_active_storage_attachments_on_blob_id"
    t.index ["record_type", "record_id", "name", "blob_id"], name: "index_active_storage_attachments_uniqueness", unique: true
  end

  create_table "active_storage_blobs", force: :cascade do |t|
    t.string "key", null: false
    t.string "filename", null: false
    t.string "content_type"
    t.text "metadata"
    t.string "service_name", null: false
    t.bigint "byte_size", null: false
    t.string "checksum"
    t.datetime "created_at", null: false
    t.index ["key"], name: "index_active_storage_blobs_on_key", unique: true
  end

  create_table "active_storage_variant_records", force: :cascade do |t|
    t.integer "blob_id", null: false
    t.string "variation_digest", null: false
    t.index ["blob_id", "variation_digest"], name: "index_active_storage_variant_records_uniqueness", unique: true
  end

  create_table "broadcast_playlist_items", force: :cascade do |t|
    t.integer "broadcast_id", null: false
    t.string "media_type", default: "video", null: false
    t.integer "position", default: 0, null: false
    t.integer "duration_seconds", default: 10, null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "transition_style", default: "fade", null: false
    t.integer "transition_duration_ms", default: 600, null: false
    t.string "video_duration_mode", default: "image_duration", null: false
    t.index ["broadcast_id", "position"], name: "index_playlist_items_on_broadcast_and_position"
    t.index ["broadcast_id"], name: "index_broadcast_playlist_items_on_broadcast_id"
  end

  create_table "broadcasts", force: :cascade do |t|
    t.string "name"
    t.string "video"
    t.string "command"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "stream_url"
    t.string "wallpaper"
    t.integer "process_pid"
    t.string "status", default: "stopped"
    t.boolean "show_widgets", default: false, null: false
    t.binary "ts_video"
    t.datetime "event_date"
    t.string "orientation"
    t.string "playback_app_type", default: "official_app", null: false
    t.string "tv_device_type", default: "normal_tv", null: false
    t.string "tv_ip"
    t.string "tv_mac_address"
    t.integer "adb_port", default: 5555, null: false
    t.string "tv_power_on_time"
    t.string "tv_power_off_time"
    t.text "tv_disabled_weekdays"
    t.datetime "last_tv_power_on_at"
    t.datetime "last_tv_power_off_at"
    t.string "last_tv_power_action"
    t.string "last_tv_power_action_status"
    t.text "last_tv_power_action_message"
    t.datetime "last_tv_power_action_at"
    t.string "official_app_page_url"
    t.integer "official_app_switch_interval_seconds"
    t.integer "official_app_page_duration_seconds"
    t.string "official_app_transition_style"
    t.integer "official_app_transition_duration_ms"
    t.string "official_app_rotation_trigger"
    t.string "official_app_switch_interval_unit"
    t.string "official_app_page_duration_unit"
    t.boolean "official_app_web_enabled", default: false, null: false
    t.string "app_device_token"
    t.datetime "app_device_registered_at"
    t.datetime "app_device_last_seen_at"
    t.string "app_player_presence_status", default: "offline", null: false
    t.datetime "app_player_presence_updated_at"
    t.integer "current_player_playlist_item_id"
    t.datetime "current_player_playlist_item_updated_at"
    t.string "adb_host"
    t.integer "tv_volume_percent", default: 50, null: false
    t.datetime "tv_volume_synced_at"
    t.string "last_known_tv_power_status"
    t.datetime "last_known_tv_power_status_at"
    t.integer "saved_broadcast_playlist_id"
    t.boolean "playlist_sync_enabled", default: false, null: false
    t.datetime "playlist_sync_started_at"
    t.boolean "widget_bar_edge_spacing_enabled", default: true, null: false
    t.integer "current_player_position_ms", default: 0, null: false
    t.datetime "playlist_resync_requested_at"
    t.string "playlist_resync_reason"
    t.boolean "keep_app_foreground_enabled", default: false, null: false
    t.datetime "last_app_foreground_reopen_at"
    t.string "last_app_foreground_reopen_message"
    t.boolean "presentation_mode_enabled", default: false, null: false
    t.string "presentation_command"
    t.integer "presentation_command_version", default: 0, null: false
    t.datetime "presentation_command_updated_at"
    t.boolean "presentation_paused", default: false, null: false
    t.boolean "official_app_web_only", default: false, null: false
    t.index ["app_device_token"], name: "index_broadcasts_on_app_device_token", unique: true
    t.index ["current_player_playlist_item_id"], name: "index_broadcasts_on_current_player_playlist_item_id"
    t.index ["saved_broadcast_playlist_id"], name: "index_broadcasts_on_saved_broadcast_playlist_id"
  end

  create_table "desktop_agents", force: :cascade do |t|
    t.string "name", null: false
    t.string "token_digest"
    t.string "hostname"
    t.string "ip_address"
    t.string "agent_version"
    t.string "last_wallpaper_version"
    t.datetime "last_seen_at"
    t.datetime "last_wallpaper_applied_at"
    t.boolean "enabled", default: true, null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "pairing_secret_digest"
    t.string "approval_code"
    t.string "status", default: "approved", null: false
    t.datetime "approved_at"
    t.datetime "paired_at"
    t.integer "desktop_group_id"
    t.index ["approval_code"], name: "index_desktop_agents_on_approval_code"
    t.index ["desktop_group_id"], name: "index_desktop_agents_on_desktop_group_id"
    t.index ["enabled"], name: "index_desktop_agents_on_enabled"
    t.index ["last_seen_at"], name: "index_desktop_agents_on_last_seen_at"
    t.index ["pairing_secret_digest"], name: "index_desktop_agents_on_pairing_secret_digest"
    t.index ["status"], name: "index_desktop_agents_on_status"
    t.index ["token_digest"], name: "index_desktop_agents_on_token_digest", unique: true
  end

  create_table "desktop_groups", force: :cascade do |t|
    t.string "name", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["name"], name: "index_desktop_groups_on_name", unique: true
  end

  create_table "licenses", force: :cascade do |t|
    t.string "token"
    t.date "valid_until"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
  end

  create_table "saved_broadcast_playlist_items", force: :cascade do |t|
    t.integer "saved_broadcast_playlist_id", null: false
    t.integer "media_blob_id", null: false
    t.string "media_type", default: "video", null: false
    t.integer "position", default: 0, null: false
    t.integer "duration_seconds", default: 10, null: false
    t.string "transition_style", default: "fade", null: false
    t.integer "transition_duration_ms", default: 600, null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["media_blob_id"], name: "index_saved_playlist_items_on_media_blob_id"
    t.index ["saved_broadcast_playlist_id", "position"], name: "index_saved_playlist_items_on_playlist_and_position"
    t.index ["saved_broadcast_playlist_id"], name: "index_saved_playlist_items_on_playlist_id"
  end

  create_table "saved_broadcast_playlists", force: :cascade do |t|
    t.string "name", null: false
    t.integer "image_duration_seconds", default: 10, null: false
    t.string "transition_style", default: "fade", null: false
    t.integer "transition_duration_ms", default: 600, null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.string "video_duration_mode", default: "image_duration", null: false
    t.boolean "sync_enabled", default: false, null: false
  end

  create_table "schedules", force: :cascade do |t|
    t.datetime "start_date"
    t.datetime "end_date"
    t.string "video"
    t.integer "broadcast_id", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.boolean "applied", default: false
    t.integer "previous_video_id"
    t.index ["broadcast_id"], name: "index_schedules_on_broadcast_id"
  end

  create_table "streaming_configurations", force: :cascade do |t|
    t.string "server_ip", null: false
    t.integer "port", default: 8080
    t.string "server_name", default: "SoftexTv"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.boolean "widgets", default: false
    t.integer "pid"
    t.boolean "google_oauth_enabled", default: false, null: false
    t.string "google_client_id"
    t.text "google_client_secret"
    t.boolean "widgets_ffmpeg_enabled", default: true, null: false
    t.boolean "widgets_official_app_enabled", default: true, null: false
    t.string "widget_bar_style", default: "transparent_blur", null: false
    t.string "widget_bar_color", default: "#0b1020", null: false
    t.integer "widget_bar_opacity", default: 72, null: false
    t.boolean "widget_bar_blur_enabled", default: true, null: false
    t.string "widget_bar_behavior", default: "fixed", null: false
    t.integer "widget_bar_show_seconds", default: 8, null: false
    t.string "widget_bar_content_mode", default: "time_weather", null: false
    t.string "widget_bar_show_unit", default: "seconds", null: false
    t.integer "widget_bar_appear_seconds", default: 0, null: false
    t.string "widget_bar_appear_unit", default: "seconds", null: false
    t.integer "widget_bar_hide_seconds", default: 0, null: false
    t.string "widget_bar_hide_unit", default: "seconds", null: false
    t.string "widget_bar_weather_api_url", default: "https://labs.aponti.org.br/api/current_data?station=estacao_01", null: false
    t.string "widget_bar_animation", default: "slide", null: false
    t.string "widget_bar_layout_mode", default: "overlay", null: false
    t.string "widget_bar_weather_test_condition", default: "real", null: false
    t.integer "widget_bar_edge_spacing", default: 0, null: false
  end

  create_table "system_notifications", force: :cascade do |t|
    t.string "title", null: false
    t.text "message"
    t.string "severity", default: "info", null: false
    t.string "event_key", null: false
    t.string "source_type"
    t.integer "source_id"
    t.text "metadata"
    t.datetime "read_at"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["created_at"], name: "index_system_notifications_on_created_at"
    t.index ["read_at"], name: "index_system_notifications_on_read_at"
    t.index ["severity"], name: "index_system_notifications_on_severity"
    t.index ["source_type", "source_id"], name: "index_system_notifications_on_source_type_and_source_id"
  end

  create_table "user_activity_logs", force: :cascade do |t|
    t.integer "user_id"
    t.string "user_email", null: false
    t.string "action_key", null: false
    t.string "action_label", null: false
    t.string "controller_name", null: false
    t.string "action_name", null: false
    t.string "request_method", null: false
    t.string "path", null: false
    t.string "ip_address"
    t.string "user_agent"
    t.integer "status"
    t.string "record_type"
    t.string "record_id"
    t.text "request_params"
    t.datetime "occurred_at", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["action_key"], name: "index_user_activity_logs_on_action_key"
    t.index ["controller_name", "action_name"], name: "index_user_activity_logs_on_controller_name_and_action_name"
    t.index ["occurred_at"], name: "index_user_activity_logs_on_occurred_at"
    t.index ["user_id"], name: "index_user_activity_logs_on_user_id"
  end

  create_table "users", force: :cascade do |t|
    t.string "email", default: "", null: false
    t.string "encrypted_password", default: "", null: false
    t.string "reset_password_token"
    t.datetime "reset_password_sent_at"
    t.datetime "remember_created_at"
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.boolean "admin"
    t.datetime "last_accessed_at"
    t.datetime "last_signed_out_at"
    t.datetime "onboarding_seen_at"
    t.index ["email"], name: "index_users_on_email", unique: true
    t.index ["last_accessed_at"], name: "index_users_on_last_accessed_at"
    t.index ["last_signed_out_at"], name: "index_users_on_last_signed_out_at"
    t.index ["reset_password_token"], name: "index_users_on_reset_password_token", unique: true
  end

  create_table "video_editor_projects", force: :cascade do |t|
    t.string "title", default: "Sem titulo", null: false
    t.string "orientation", default: "landscape", null: false
    t.text "project_data"
    t.integer "rendered_blob_id"
    t.integer "user_id", null: false
    t.datetime "created_at", null: false
    t.datetime "updated_at", null: false
    t.index ["user_id"], name: "index_video_editor_projects_on_user_id"
  end

  add_foreign_key "active_storage_attachments", "active_storage_blobs", column: "blob_id"
  add_foreign_key "active_storage_variant_records", "active_storage_blobs", column: "blob_id"
  add_foreign_key "broadcast_playlist_items", "broadcasts"
  add_foreign_key "broadcasts", "saved_broadcast_playlists"
  add_foreign_key "desktop_agents", "desktop_groups"
  add_foreign_key "saved_broadcast_playlist_items", "active_storage_blobs", column: "media_blob_id"
  add_foreign_key "saved_broadcast_playlist_items", "saved_broadcast_playlists"
  add_foreign_key "schedules", "broadcasts"
  add_foreign_key "user_activity_logs", "users"
  add_foreign_key "video_editor_projects", "users"
end
