class DashboardsController < ApplicationController
  def index
    @broadcasts = Broadcast.all.to_a
    @saved_playlists = SavedBroadcastPlaylist.order(updated_at: :desc).includes(:items)
  end
end
