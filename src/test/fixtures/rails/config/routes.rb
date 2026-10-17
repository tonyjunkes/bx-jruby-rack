Rails.application.routes.draw do
  get '/', to: 'notes#index'
  post '/form', to: 'notes#form_create'
  post '/upload', to: 'notes#upload'
  get '/events', to: 'events#index'
  get '/health', to: 'notes#health'
  post '/notes', to: 'notes#create'
  get '/notes/:id', to: 'notes#show'
end
