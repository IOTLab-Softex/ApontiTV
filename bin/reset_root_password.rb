root_email = ENV.fetch("ROOT_USER_EMAIL", "root@apontitv.local").strip.downcase
password = ENV.fetch("APONTI_ROOT_NEW_PASSWORD")

user = User.find_by("LOWER(email) = ?", root_email)
abort("Usuário root não encontrado: #{root_email}") unless user&.local_root?

user.update!(password: password, password_confirmation: password)
puts "ROOT_PASSWORD_UPDATED=#{root_email}"
