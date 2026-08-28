ENV["BUNDLE_GEMFILE"] ||= File.expand_path("../Gemfile", __dir__)

require "bundler/setup" # Set up gems listed in the Gemfile.
# Bootsnap is disabled on this Windows environment because App Control blocks
# native extensions such as msgpack/date when loaded from the project bundle.
