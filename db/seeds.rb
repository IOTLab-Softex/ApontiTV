# This file should contain all the record creation needed to seed the database with its default values.
# The data can then be loaded with the bin/rails db:seed command (or created alongside the database with db:setup).
#
# Examples:
#
#   movies = Movie.create([{ name: "Star Wars" }, { name: "Lord of the Rings" }])
#   Character.create(name: "Luke", movie: movies.first)

root_email = ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local")
root_password = ENV.fetch("ROOT_USER_PASSWORD", "root123456")

root_user = User.find_or_initialize_by(email: root_email)
root_user.password = root_password
root_user.password_confirmation = root_password
root_user.admin = true if root_user.respond_to?(:admin=)
root_user.save!

puts "Usuario root padrao pronto: #{root_email}"
