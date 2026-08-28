# Be sure to restart your server when you modify this file.

# Version of your assets, change this if you want to expire all your assets.
Rails.application.config.assets.version = "1.0"

Rails.application.config.assets.configure do |env|
  cache_path = Rails.root.join("tmp/cache/assets-#{ENV.fetch('USERNAME', 'local')}")
  FileUtils.mkdir_p(cache_path)
  env.cache = Sprockets::Cache::FileStore.new(cache_path.to_s)
end

# Add additional assets to the asset load path.
# Rails.application.config.assets.paths << Emoji.images_path

# Precompile additional assets.
# application.js, application.css, and all non-JS/CSS in the app/assets
# folder are already added.
# Rails.application.config.assets.precompile += %w( admin.js admin.css )
