class PagesController < ApplicationController
  skip_before_action :authenticate_user!, only: [:licenca_invalida, :releases]
  skip_before_action :check_license

  def licenca_invalida
    render layout: false
  end

  def releases
    send_file Rails.root.join("docs", "APONTI_TV_RELEASES.html"),
              type: "text/html",
              disposition: "inline"
  end
end
  
