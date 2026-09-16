require "test_helper"
require "minitest/mock"

class Andar360AuthenticationTest < ActionDispatch::IntegrationTest
  self.fixture_table_names = []

  test "temporary password login requires a new password before accessing the app" do
    temporary = identity.merge("force_password_change" => true)
    Andar360Identity.stub(:authenticate, temporary) do
      post user_session_path(format: :json), params: {user: {email: identity["email"], password: "temporary-password"}}
      assert_response :success
      assert_includes response.parsed_body["redirect_url"], "/shared_password/edit"
    end
    Andar360Identity.stub(:authorize, temporary) do
      get root_path
      assert_redirected_to edit_shared_password_path(locale: I18n.locale)
      post user_session_path(format: :json), params: {user: {email: identity["email"], password: "temporary-password"}}
      assert_response :success
      assert_includes response.parsed_body["redirect_url"], "/shared_password/edit"
      get edit_shared_password_path, headers: {"Accept" => "application/json"}
      assert_response :success
      assert_includes response.parsed_body["redirect_url"], "/shared_password/edit.html"
      get edit_shared_password_path
      assert_response :success
      assert_select "input[name=password]"
      assert_select ".login-box", count: 1
      assert_select ".login-logo", count: 1
      assert_select ".app-sidebar", count: 0
      assert_select ".app-banner", count: 0
      get users_path
      assert_redirected_to edit_shared_password_path(locale: I18n.locale)
      get media_library_path
      assert_redirected_to edit_shared_password_path(locale: I18n.locale)
      updated = identity.merge("session_version" => "b" * 64, "force_password_change" => false)
      Andar360Identity.stub(:call, updated) do
        patch shared_password_path, params: {password: "new-password", password_confirmation: "new-password"}
        assert_response :redirect
      end
    end
  end

  test "a logged-in user can change the shared password after confirming the current one" do
    Andar360Identity.stub(:authenticate, identity) do
      post user_session_path(format: :json), params: { user: { email: identity["email"], password: "current-password" } }
    end
    Andar360Identity.stub(:authorize, identity) do
      get edit_shared_password_path
      assert_response :success
      assert_select "input[name=current_password]"
      Andar360Identity.stub(:authenticate, nil) do
        patch shared_password_path, params: { current_password: "wrong", password: "new-password", password_confirmation: "new-password" }
        assert_response :unprocessable_entity
      end
      updated = identity.merge("session_version" => "c" * 64, "force_password_change" => false)
      Andar360Identity.stub(:authenticate, identity) do
        Andar360Identity.stub(:call, updated) do
          patch shared_password_path, params: { current_password: "current-password", password: "new-password", password_confirmation: "new-password" }
          assert_response :redirect
        end
      end
    end
  end

  test "profile menu password page uses the legacy internal screen and saves in Andar360" do
    Andar360Identity.stub(:authenticate, identity) do
      post user_session_path(format: :json), params: { user: { email: identity["email"], password: "current-password" } }
    end
    Andar360Identity.stub(:authorize, identity) do
      get edit_user_registration_path
      assert_response :success
      assert_select ".profile-edit-page", count: 1
      assert_select "input[name='user[current_password]']"
      updated = identity.merge("session_version" => "d" * 64, "force_password_change" => false)
      Andar360Identity.stub(:authenticate, identity) do
        Andar360Identity.stub(:call, updated) do
          put user_registration_path, params: { user: { current_password: "current-password", password: "new-password", password_confirmation: "new-password" } }
          assert_redirected_to root_path(locale: I18n.locale)
        end
      end
    end
  end

  test "profile photo is retrieved for the signed-in identity only" do
    Andar360Identity.stub(:authenticate, identity) do
      post user_session_path(format: :json), params: {user: {email: identity["email"], password: "shared-password"}}
    end
    Andar360Identity.stub(:authorize, identity) do
      photo = {"data" => Base64.strict_encode64("image-bytes"), "content_type" => "image/png"}
      Andar360Identity.stub(:call, ->(action, attributes) {
        assert_equal "photo", action
        assert_equal identity["id"], attributes[:user_id]
        photo
      }) do
        get profile_photo_path, params: {user_id: 999}
        assert_response :success
        assert_equal "image/png", response.media_type
        assert_equal "image-bytes", response.body
      end
    end
  end

  test "local root can log in without Andar360 but needs its local password" do
    root = User.create!(email: ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local"), password: "local-root-test-password", admin: true)
    Andar360Identity.stub(:authenticate, ->(*) { raise "Must not contact Andar360" }) do
      assert_nil User.authenticate_with_andar360("root", "wrong")
      assert_equal root, User.authenticate_with_andar360(root.email, "local-root-test-password")
      post user_session_path(format: :json), params: {user: {email: "root", password: "local-root-test-password"}}
      assert_response :success
      key, salt = User.serialize_into_session(root)
      assert User.serialize_from_session(key, salt)
      root.update!(password: "replacement-root-password")
      assert_nil User.serialize_from_session(key, salt)
    end
  end

  test "Andar360 identity cannot claim the local root account" do
    root = User.create!(email: ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local"), password: "local-root-test-password", admin: true)
    assert_nil User.from_andar360(identity.merge("email" => root.email))
    assert_nil root.reload.andar360_user_id
  end

  def identity
    { "id" => 123, "email" => "andar360-test@example.test", "allowed" => true, "session_version" => "a" * 64 }
  end

  test "authorized Andar360 users are provisioned without administrator privileges" do
    Andar360Identity.stub(:authenticate, identity) do
      post user_session_path(format: :json), params: { user: { email: "12345678901", password: "shared-password" } }
      assert_response :success
      user = User.find_by!(andar360_user_id: 123)
      refute user.admin?
      assert_equal identity["email"], user.email
    end
  end

  test "legacy local password does not bypass Andar360 and denial does not provision a user" do
    User.create!(email: "local-only@example.test", password: "local-password")
    Andar360Identity.stub(:authenticate, nil) do
      assert_no_difference "User.count" do
        post user_session_path(format: :json), params: { user: { email: "local-only@example.test", password: "local-password" } }
      end
      assert_response :unauthorized
    end
  end

  test "HTML login is subject to the same authorization" do
    Andar360Identity.stub(:authenticate, nil) do
      post user_session_path, params: { user: { email: "unknown@example.test", password: "invalid" } }
      assert_response :unauthorized
      assert_select "a", text: "Esqueci minha senha"
    end
  end

  test "password recovery and old reset tokens use Andar360" do
    get new_user_password_path
    assert_redirected_to "http://andar360.ddns.net/recuperar-senha"
    get edit_user_password_path(reset_password_token: "old-local-token")
    assert_redirected_to "http://andar360.ddns.net/recuperar-senha"
  end

  test "integration outage fails closed" do
    Andar360Identity.stub(:authenticate, ->(*) { raise Andar360Identity::Unavailable }) do
      post user_session_path(format: :json), params: { user: { email: "unknown@example.test", password: "invalid" } }
      assert_response :service_unavailable
    end
  end

  test "password changes are blocked while an authenticated user is offline" do
    cached = User.from_andar360(identity)
    cached.cache_andar360_identity!(identity, password: "cached-password", login: identity["email"])
    Andar360Identity.stub(:authenticate, ->(*) { raise Andar360Identity::Unavailable }) do
      Andar360Identity.stub(:authorize, ->(*) { raise Andar360Identity::Unavailable }) do
        post user_session_path(format: :json), params: { user: { email: identity["email"], password: "cached-password" } }
        assert_response :success
      end
    end
    Andar360Identity.stub(:authorize, ->(*) { raise Andar360Identity::Unavailable }) do
      get edit_user_registration_path
      assert_response :success
      put user_registration_path, params: { user: { current_password: "cached-password", password: "new-password", password_confirmation: "new-password" } }
      assert_response :service_unavailable
      assert_includes response.body, "bloqueada enquanto o Andar360 estiver indisponível"
    end
  end
end
