require 'sinatra/base'
require 'securerandom'
require 'rack/protection/authenticity_token'

class Example < Sinatra::Base
  set :environment, ENV.fetch('RACK_ENV').to_sym
  set :run, false
  set :show_exceptions, false
  set :raise_errors, true

  set :sessions, key: '_bx_sinatra', secret: ENV.fetch('SESSION_SECRET', 'fixture-only-secret-' * 8), same_site: :lax
  set :public_folder, File.expand_path('public', __dir__)
  set :views, File.expand_path('views', __dir__)
  use Rack::Protection::AuthenticityToken, allow_if: ->(env) { !%w[/form /upload].include?(env['PATH_INFO']) }

  get '/form' do
    @token = Rack::Protection::AuthenticityToken.token(session)
    @saved = session[:saved]
    erb :form
  end

  post '/form' do
    session[:saved] = params.fetch('text')
    redirect to('/form'), 303
  end

  post '/upload' do
    content_type 'application/octet-stream'
    params.fetch('upload').fetch(:tempfile).read
  end

  get '/events' do
    content_type 'text/event-stream'
    stream do |out|
      2.times do |i|
        out << "data: #{i}\n\n"
        sleep 0.05
      end
    end
  end

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
