require "test_helper"

class WebLoginAuthorizationTest < ActiveSupport::TestCase
  self.fixture_table_names = []

  test "credentials require both bound token and configured source IP" do
    controller = BroadcastsController.new
    request = ActionDispatch::TestRequest.create
    controller.request = request
    broadcast = Struct.new(:app_device_token, :tv_ip) do
      def reload = self
    end.new("bound-test-token", "192.168.1.150")
    request.set_header("REMOTE_ADDR", "192.168.1.150")
    refute controller.send(:web_login_device_authorized?, broadcast)
    request.headers["X-TV-Device-Token"] = "wrong-token"
    refute controller.send(:web_login_device_authorized?, broadcast)
    request.headers["X-TV-Device-Token"] = "bound-test-token"
    assert controller.send(:web_login_device_authorized?, broadcast)
    controller.request = ActionDispatch::TestRequest.create
    controller.request.headers["X-TV-Device-Token"] = "bound-test-token"
    controller.request.headers["X-TV-Device-Ips"] = "192.168.1.150"
    controller.request.set_header("REMOTE_ADDR", "192.168.1.151")
    refute controller.send(:web_login_device_authorized?, broadcast)
  end
end
