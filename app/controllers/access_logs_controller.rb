class AccessLogsController < ApplicationController
  def index
    scope = UserActivityLog.includes(:user).recent

    if current_user.admin?
      @users = User.order(:email)
      scope = scope.where(user_id: params[:user_id]) if params[:user_id].present?
    else
      scope = scope.where(user_id: current_user.id)
    end

    if params[:q].present?
      query = "%#{ActiveRecord::Base.sanitize_sql_like(params[:q].to_s.strip)}%"
      scope = scope.where(
        "action_label LIKE :query OR path LIKE :query OR user_email LIKE :query",
        query: query
      )
    end

    @logs = scope.limit(300)
  end
end
