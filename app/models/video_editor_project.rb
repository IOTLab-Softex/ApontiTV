class VideoEditorProject < ApplicationRecord
  belongs_to :user
  has_many_attached :media_files

  def rendered_blob
    ActiveStorage::Blob.find_by(id: rendered_blob_id)
  end

  def orientation_label
    orientation == "portrait" ? "Retrato 9:16" : "Paisagem 16:9"
  end
end
