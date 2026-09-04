class OnboardingController < ApplicationController
  before_action :authenticate_user!

  def complete
    current_user.update_column(:onboarding_seen_at, Time.current) if current_user.onboarding_seen_at.nil?
    head :no_content
  end
end
