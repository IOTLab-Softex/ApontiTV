require "test_helper"

class TvMonitoringControllerTest < ActionDispatch::IntegrationTest
  self.fixture_table_names = []

  setup do
    @previous_token = ENV["APONTI_MONITORING_TOKEN"]
    ENV["APONTI_MONITORING_TOKEN"] = "monitoring-test-token"
  end

  teardown do
    ENV["APONTI_MONITORING_TOKEN"] = @previous_token
  end

  test "requires its own bearer token" do
    get "/monitoring/tvs"
    assert_response :unauthorized
    get "/monitoring/tvs", headers: { "Authorization" => "Bearer incorrect" }
    assert_response :unauthorized
    get "/monitoring/tvs", headers: { "Authorization" => "Bearer monitoring-test-token" }
    assert_response :success
    assert_equal 2, response.parsed_body["schema_version"]
    assert_equal "no-store", response.headers["Cache-Control"]
  end

  test "documents every monitoring status code" do
    assert_equal [1, 2, 3, 4, 5], TvMonitoringSnapshot::STATUS_GUIDE[:overall].map(&:first)
    assert_equal [[0, "Desligada"], [1, "Ligada"]], TvMonitoringSnapshot::STATUS_GUIDE[:tv]
    assert_equal [[0, "Fechado"], [1, "Aberto"]], TvMonitoringSnapshot::STATUS_GUIDE[:app]
    assert_equal [0, 1, 2, 3, 4], TvMonitoringSnapshot::STATUS_GUIDE[:content].map(&:first)
  end

  test "snapshot combines one power reading with the app and content state" do
    tv = Broadcast.new(id: 123, name: "Test TV", tv_ip: "192.0.2.1", status: "running",
      app_player_presence_status: "playing", app_player_presence_updated_at: 10.seconds.ago,
      official_app_login_password: "must-not-leak")
    tv_on = ->(_tv) { :on }
    fresh = TvMonitoringSnapshot.call([tv], power_status_resolver: tv_on)[:tvs].first
    assert_equal 1, fresh[:tv_on]
    assert_equal 1, fresh[:overall_status]
    assert_equal 1, fresh[:app_online]
    assert_equal 2, fresh[:app_status]
    assert_equal 2, fresh[:operating_status]
    refute_includes fresh.to_json, "must-not-leak"
    tv.app_player_presence_updated_at = 60.seconds.ago
    stale = TvMonitoringSnapshot.call([tv], power_status_resolver: tv_on)[:tvs].first
    assert_equal 0, stale[:app_online]
    assert_equal 0, stale[:operating_status]
    assert_equal 2, stale[:overall_status]
    tv.app_player_presence_status = "online"
    tv.app_player_presence_updated_at = 5.seconds.ago
    tv.official_app_web_enabled = true
    tv.official_app_web_only = true
    tv.official_app_page_url = "https://example.test/dashboard"
    web = TvMonitoringSnapshot.call([tv], power_status_resolver: tv_on)[:tvs].first
    assert_equal 1, web[:web_only]
    assert_equal 1, web[:operating_status]
    tv.name = "Renamed TV"
    assert_equal "Renamed TV", TvMonitoringSnapshot.call([tv], power_status_resolver: tv_on)[:tvs].first[:name]
    powered_off = TvMonitoringSnapshot.call([tv], power_status_resolver: ->(_tv) { :off })[:tvs].first
    assert_equal 0, powered_off[:tv_on]
    assert_equal 3, powered_off[:overall_status]
    assert_empty TvMonitoringSnapshot.call([], power_status_resolver: tv_on)[:tvs]
  end
end
