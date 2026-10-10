require 'sinatra/base'

class Example < Sinatra::Base
  set :environment, ENV.fetch('RACK_ENV').to_sym
  set :run, false
  set :show_exceptions, false
  set :raise_errors, true

  get '/' do
    "Sinatra #{Sinatra::VERSION}"
  end

  post '/echo' do
    content_type 'application/octet-stream'
    request.body.read
  end

  get '/cookie' do
    response.set_cookie('sinatra', value: 'works', path: request.script_name)
    'cookie'
  end

  get '/redirect' do
    redirect to('/'), 302
  end

  get '/failure' do
    raise 'private Sinatra failure detail'
  end
end

run Example
