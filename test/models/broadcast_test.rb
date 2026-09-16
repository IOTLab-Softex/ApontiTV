require "test_helper"

class BroadcastTest < ActiveSupport::TestCase
  self.fixture_table_names = []
  test "web login encrypts password and preserves it on blank edits" do
    broadcast = Broadcast.new(official_app_login_password: "test-only-secret")
    assert_equal "test-only-secret", broadcast.official_app_login_password
    refute_includes broadcast.official_app_login_password_ciphertext, "test-only-secret"
    ciphertext = broadcast.official_app_login_password_ciphertext
    broadcast.official_app_login_password = ""
    assert_equal ciphertext, broadcast.official_app_login_password_ciphertext
    broadcast.official_app_login_password = "replacement"
    assert_equal "replacement", broadcast.official_app_login_password
  end

  test "web credentials require explicit payload authorization and enabled page" do
    broadcast = Broadcast.new(official_app_web_enabled: true,
      official_app_page_url: "http://example.test/dashboard",
      official_app_login_enabled: true, official_app_login_username: "viewer",
      official_app_login_password: "test-only-secret")
    assert_nil broadcast.official_app_payload[:login]
    assert_equal "viewer", broadcast.official_app_payload(include_login: true)[:login][:username]
    broadcast.official_app_login_enabled = false
    assert_nil broadcast.official_app_payload(include_login: true)[:login]
    broadcast.official_app_login_enabled = true
    broadcast.official_app_web_enabled = false
    assert_nil broadcast.official_app_payload(include_login: true)[:login]
  end

  test "enabled login requires credentials" do
    broadcast = Broadcast.new(official_app_login_enabled: true)
    broadcast.valid?
    assert broadcast.errors[:official_app_login_username].present?
    assert broadcast.errors[:official_app_login_password].present?
  end

  test "official app web predicates are public" do
    broadcast = Broadcast.new(
      official_app_web_enabled: "1",
      official_app_web_only: "1"
    )

    assert broadcast.official_app_web_enabled?
    assert broadcast.official_app_web_only?
  end

  test "recent screen state has priority over app presence for TV power" do
    broadcast = Broadcast.new(
      app_player_presence_status: "playing",
      app_player_presence_updated_at: Time.current,
      app_screen_on: false,
      app_screen_status_updated_at: Time.current
    )

    assert_equal :off, broadcast.tv_power_status
    broadcast.app_screen_on = true
    assert_equal :on, broadcast.tv_power_status
  end
end
