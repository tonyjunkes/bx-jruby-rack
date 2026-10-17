run lambda { |env|
  if env['PATH_INFO'] == '/events'
    body = lambda do |stream|
      3.times do |i|
        stream << "data: #{i}\n\n"
        stream.flush
        sleep 0.2
      end
    end
    [200, {'content-type' => 'text/event-stream', 'cache-control' => 'no-cache'}, body]
  else
    [200, {'content-type' => 'text/html; charset=utf-8'}, ['<h1>Ruby in BoxLang</h1><a href="/ruby/events">SSE events</a>']]
  end
}
