class UserActivityLog < ApplicationRecord
  belongs_to :user, optional: true

  scope :recent, -> { order(occurred_at: :desc, id: :desc) }

  def successful?
    status.to_i.between?(200, 399)
  end
end
