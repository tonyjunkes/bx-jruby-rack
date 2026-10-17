class EventsController < ActionController::Base
  include ActionController::Live

  def index
    response.headers['Content-Type'] = 'text/event-stream'
    if params[:infinite]
      loop do
        response.stream.write "data: #{'x' * 8192}\n\n"
        sleep 0.01
      end
    else
      2.times do |i|
        response.stream.write "data: #{i}\n\n"
        sleep 0.05
      end
    end
  ensure
    response.stream.close
    File.write(ENV['BX_RAILS_STREAM_CLOSED'], 'closed') if ENV['BX_RAILS_STREAM_CLOSED']
  end
end
