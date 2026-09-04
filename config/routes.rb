Rails.application.routes.draw do
 
 
  devise_for :users, skip: [:registrations], controllers: {
    sessions: "users/sessions",
    omniauth_callbacks: "users/omniauth_callbacks"
  }
  get '/licenca_invalida', to: 'pages#licenca_invalida', as: :licenca_invalida
  get '/releases', to: 'pages#releases', as: :releases
  get  '/licenca', to: 'license#new'
  post '/licenca', to: 'license#create'
  
  as :user do
    get 'users/edit' => 'devise/registrations#edit', as: :edit_user_registration
    put 'users' => 'devise/registrations#update', as: :user_registration
  end
  resources :users
  resources :access_logs, only: [:index]
  resources :notifications, only: [:index] do
    post :mark_all_read, on: :collection
  end
  post "onboarding/complete", to: "onboarding#complete", as: :onboarding_complete
  resources :desktop_agents, only: [:index, :create, :update, :destroy] do
    post :approve, on: :member
  end
  resources :desktop_groups, only: [:create, :destroy]

  namespace :agent do
    resources :pairings, only: [:create, :show], controller: "/agent_pairings"
    resource :wallpaper, only: [:show], controller: "/agent_wallpapers" do
      post :applied
    end
  end

  get    "media_library",          to: "media_library#index",  as: "media_library"
  post   "media_library",          to: "media_library#create"
  get    "media_library/:id/thumbnail", to: "media_library#thumbnail", as: "media_library_thumbnail"
  delete "media_library/:id",      to: "media_library#destroy", as: "media_library_item"

  resources :broadcast_playlists, only: [:index, :show, :create, :update, :destroy] do
    collection do
      post :upload_media
      get :media_library
    end
  end

  get    "video_editor",          to: "video_editor#index",   as: "video_editor"
  get    "video_editor/new",      to: "video_editor#new",     as: "new_video_editor"
  post   "video_editor/save_draft", to: "video_editor#save_draft", as: "save_draft_video_editor"
  post   "video_editor",          to: "video_editor#create",  as: "video_editor_create"
  get    "video_editor/:id/edit", to: "video_editor#edit",    as: "edit_video_editor_project"
  delete "video_editor/:id",      to: "video_editor#destroy", as: "video_editor_project"

  # Configuração das transmissões (broadcasts)
  resources :broadcasts do
    member do
      post 'start'
      post 'stop'
      post 'power_on_tv'
      post 'power_off_tv'
      post 'volume_up_tv'
      post 'volume_down_tv'
      post 'set_volume_tv'
      post 'mute_tv'
      post 'update_power_schedule'
      post 'request_adb_authorization'
      post 'set_android_launcher'
      post 'remove_android_launcher'
      post 'open_official_app'
      post 'toggle_presentation_mode'
      post 'presentation_command'
      post 'mobile_presence'
      post 'mobile_player_status'
      get 'mobile_status'
      get 'presentation_status'
      get 'mobile_thumbnail'
      get 'mobile_video'
      get 'mobile_prepared_video'
      get 'mobile_playlist_item/:item_id', action: :mobile_playlist_item, as: :mobile_playlist_item
      get 'preview/*filename', action: :preview_stream, as: :preview_stream
    end
    collection do
      get :mobile_index
      post :publish_video
      post 'execute'
      post 'stop_all'
      post :update_power_schedule_all
      post :set_wallpaper
      get :check_tv_status
    end
  end
  
  # Rota para executar os broadcasts selecionados (botão "Play All Selected")
  post 'execute_broadcasts', to: 'broadcasts#execute'
  
  # Rota para exportar o arquivo M3U
  get 'export_m3u_broadcasts', to: 'broadcasts#export_m3u'

  # Configuração das transmissões de streaming
  resources :streaming_configurations do
    member do
      post 'start_streaming'
      post 'stop_streaming'
    end
  end
# config/routes.rb

resources :schedules, only: [:index, :create] do
  collection do
    post 'bulk_create' # Adicionando a rota para salvar múltiplos períodos
    delete :destroy_schedule
  end
end
  # Configuração das rotas para wallpapers
  resources :wallpapers, only: [:new, :create, :index] do
    post 'update_wallpaper', on: :collection
    get :status, on: :collection
  end

  # Rota para mudar o idioma
  get 'change_locale', to: 'application#change_locale', as: 'change_locale'

  # Recursos adicionais
  get 'widgets', to: 'widgets#show'
  get 'widgets/calendario', to: 'widgets#calendario'
  get 'widgets/clima', to: 'widgets#clima'
  get '/temperature', to: 'widgets#temperature'

  
  # Rota para iniciar e parar o vídeo
  post 'video/start', to: 'video#start'
  post 'video/stop', to: 'video#stop'

  # Rota raiz
  get 'dashboard', to: 'dashboards#index', as: :dashboard
  root 'dashboards#index'
end
