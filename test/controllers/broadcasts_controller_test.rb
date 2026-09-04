require "test_helper"

class BroadcastsControllerTest < ActionDispatch::IntegrationTest
  test "mobile index renders dashboard preview data" do
    get mobile_index_broadcasts_url(format: :json, locale: :pt)

    assert_response :success
  end
end
