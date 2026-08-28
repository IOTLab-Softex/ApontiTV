class NotificationsController < ApplicationController
  def index
    notifications = SystemNotification.recent.limit(80)

    respond_to do |format|
      format.html { @notifications = notifications }
      format.json do
        unread_notifications = notifications.select { |notification| notification.read_at.blank? }
        render json: {
          unread_count: unread_notifications.size,
          notifications: notifications.first(30).map { |notification| notification_payload(notification) }
        }
      end
    end
  end

  def mark_all_read
    SystemNotification.unread.update_all(read_at: Time.current, updated_at: Time.current)

    render json: { ok: true, unread_count: 0 }
  end

  private

  def notification_payload(notification)
    {
      id: notification.id,
      title: notification.title,
      message: notification.message,
      severity: notification.severity,
      icon_class: notification.icon_class,
      unread: notification.read_at.blank?,
      created_at: notification.created_at.iso8601,
      source_type: notification.source_type,
      source_id: notification.source_id
    }
  end
end
