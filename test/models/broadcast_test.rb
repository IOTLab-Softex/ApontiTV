require "test_helper"

class BroadcastTest < ActiveSupport::TestCase
  test "official app web predicates are public" do
    broadcast = Broadcast.new(
      official_app_web_enabled: "1",
      official_app_web_only: "1"
    )

    assert broadcast.official_app_web_enabled?
    assert broadcast.official_app_web_only?
  end
end
