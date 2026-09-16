class ProfilePhotosController < ApplicationController
  skip_before_action :check_license
  skip_before_action :require_shared_password_change!

  def show
    return head :not_found if current_user.local_root?
    photo = Andar360Identity.call("photo", user_id: current_user.andar360_user_id)
    return head :not_found unless photo && photo["content_type"].in?(%w[image/jpeg image/png image/webp image/gif])
    response.headers["Cache-Control"] = "private, no-store"
    send_data Base64.strict_decode64(photo.fetch("data")), type: photo["content_type"], disposition: "inline"
  rescue Andar360Identity::Unavailable, ArgumentError, KeyError
    head :not_found
  end
end
