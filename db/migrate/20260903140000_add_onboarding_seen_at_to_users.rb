class AddOnboardingSeenAtToUsers < ActiveRecord::Migration[7.0]
  def change
    add_column :users, :onboarding_seen_at, :datetime
  end
end
