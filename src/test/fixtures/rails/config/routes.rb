Rails.application.routes.draw do
  get '/health', to: 'notes#health'
  post '/notes', to: 'notes#create'
  get '/notes/:id', to: 'notes#show'
end
