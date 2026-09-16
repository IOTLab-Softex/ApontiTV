require "test_helper"
require "minitest/mock"

class Andar360IdentityTest < ActiveSupport::TestCase
  self.fixture_table_names = []

  def identity
    { "id" => 321, "email" => "identity@example.test", "allowed" => true, "session_version" => "a" * 64 }
  end

  test "permission removal and password reset invalidate serialized sessions" do
    user = User.from_andar360(identity)
    key, salt = User.serialize_into_session(user)
    Andar360Identity.stub(:authorize, identity) { assert User.serialize_from_session(key, salt) }
    Andar360Identity.stub(:authorize, nil) { assert_nil User.serialize_from_session(key, salt) }
    Andar360Identity.stub(:authorize, identity.merge("session_version" => "b" * 64)) do
      assert_nil User.serialize_from_session(key, salt)
    end
    Andar360Identity.stub(:authorize, ->(*) { raise Andar360Identity::Unavailable }) do
      assert_nil User.serialize_from_session(key, salt)
    end
  end

  test "linking a verified existing identity preserves local administrator assignment" do
    existing = User.create!(email: identity["email"], password: "local-password", admin: true)
    linked = User.from_andar360(identity)
    assert_equal existing.id, linked.id
    assert linked.admin?
    assert_equal 321, linked.andar360_user_id
  end

  test "a verified user can authenticate offline with the cached password" do
    user = User.from_andar360(identity)
    user.cache_andar360_identity!(identity, password: "cached-password", login: "12345678901")

    Andar360Identity.stub(:authenticate, ->(*) { raise Andar360Identity::Unavailable }) do
      Andar360Identity.stub(:authorize, ->(*) { raise Andar360Identity::Unavailable }) do
        cached = User.authenticate_with_andar360("12345678901", "cached-password")
        assert_equal user, cached
        assert cached.andar360_offline?
        assert cached.active_for_authentication?
        assert_nil User.authenticate_with_andar360("12345678901", "wrong-password")
      end
    end
  end

  test "offline authentication requires a prior successful verification and expires" do
    user = User.from_andar360(identity)
    user.password = "cached-password"
    user.andar360_login = "12345678901"
    user.andar360_session_version = identity["session_version"]
    user.andar360_last_verified_at = 31.days.ago
    user.save!

    Andar360Identity.stub(:authenticate, ->(*) { raise Andar360Identity::Unavailable }) do
      assert_nil User.authenticate_with_andar360("12345678901", "cached-password")
    end
  end

  test "Google requires a verified email and Andar360 permission" do
    auth = OmniAuth::AuthHash.new(info: {email: identity["email"]}, extra: {raw_info: {email_verified: true}})
    Andar360Identity.stub(:authorize, nil) { assert_nil User.from_google_oauth(auth) }
    Andar360Identity.stub(:authorize, identity) { assert User.from_google_oauth(auth) }
    auth.extra.raw_info.email_verified = false
    Andar360Identity.stub(:authorize, identity) { assert_nil User.from_google_oauth(auth) }
  end

  test "identity linking retries a transient SQLite busy error" do
    calls = 0
    original = User.method(:find_by)
    lookup = lambda do |*args|
      calls += 1
      if calls == 1
        begin
          raise SQLite3::BusyException, "database is locked"
        rescue SQLite3::BusyException
          raise ActiveRecord::StatementInvalid, "database is locked"
        end
      end
      original.call(*args)
    end
    User.stub(:find_by, lookup) do
      assert_equal identity["id"], User.from_andar360(identity).andar360_user_id
    end
    assert_equal 2, calls
  end
end
