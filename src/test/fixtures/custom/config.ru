require 'rack/lint'
require 'json'

at_exit { File.write(ENV['BX_AT_EXIT'], java.lang.Thread.current_thread.name) } if ENV['BX_AT_EXIT']
class FixtureBody
  def initialize(chunks)
    @chunks = chunks
  end

  def each
    @chunks.each { |chunk| yield chunk }
  end

  def close
    $closed_bodies = ($closed_bodies || 0) + 1
  end
end

class LargeBody < FixtureBody
  def each
    1024.times { yield 'x' * 65536 }
  end
end

class FailingBody < FixtureBody
  def each
    raise 'private body error'
  end
end

counter = 0
app = lambda do |env|
  counter += 1
  case env['PATH_INFO']
  when '/invalid-header'
    [200, {'content-type' => "text/plain\r\nx-injected: yes"}, FixtureBody.new(['secret'])]
  when '/invalid-uppercase'
    [200, {'Content-Type' => 'text/plain'}, FixtureBody.new(['secret'])]
  when '/invalid-status'
    [101, {}, FixtureBody.new(['secret'])]
  when '/invalid-length'
    [200, {'content-length' => '2'}, FixtureBody.new(['longer'])]
  when '/invalid-short'
    [200, {'content-length' => '10'}, FixtureBody.new(['short'])]
  when '/invalid-hijack'
    [200, {'rack.hijack' => ->(io) {}}, FixtureBody.new(['secret'])]
  when '/large'
    [200, {'content-type' => 'application/octet-stream'}, LargeBody.new([])]
  when '/bodyfail'
    [200, {'content-type' => 'text/plain'}, FailingBody.new([])]
  when '/exit'
    exit 7
  when '/hold'
    gate = ENV.fetch('BX_GATE')
    File.write(gate + '.started', 'started')
    sleep 0.01 while File.exist?(gate)
    [200, {'content-type' => 'text/plain'}, FixtureBody.new(['released'])]
  when '/io'
    input = env['rack.input']
    buffer = 'old'
    raise 'buffer identity' unless input.read(2, buffer).equal?(buffer)
    raise 'binary mode' unless buffer.encoding == Encoding::BINARY
    rest = input.read
    raise 'EOF read' unless input.read(1).nil? && input.read == '' && input.read(0) == ''
    [200, {'content-type' => 'text/plain'}, ['io ok']]
  when '/multipart'
    params = Rack::Request.new(env).params
    [200, {'content-type' => 'application/octet-stream'}, [params.fetch('upload').fetch(:tempfile).read.b]]
  when '/state'
    [200, {'content-type' => 'application/json'}, [JSON.generate({env: ENV['BX_ISOLATED'], thread: java.lang.Thread.current_thread.name, counter: counter, closed: $closed_bodies || 0, finished: $finished || []})]]
  when '/hooks'
    env['rack.response_finished'] << ->(_, _, _, error) { ($finished ||= []) << "first:#{error.nil?}" }
    env['rack.response_finished'] << ->(_, _, _, error) { ($finished ||= []) << "second:#{error.nil?}" }
    [200, {'content-type' => 'text/plain'}, FixtureBody.new(['hooks'])]
  when '/callable'
    [200, {'content-type' => 'text/plain'}, ->(stream) { stream.write('unsupported') }]
  when '/binary'
    [200, {'content-type' => 'application/octet-stream'}, FixtureBody.new(["\x00\xff\x80hello".b])]
  when '/echo'
    [200, {'content-type' => 'application/octet-stream'}, FixtureBody.new([env['rack.input'].read])]
  when '/cookies'
    [200, {'set-cookie' => ['a=1; Path=/', 'b=2; Path=/'], 'content-type' => 'text/plain'}, ['cookies']]
  when '/empty'
    [204, {}, []]
  when '/missing'
    [404, {'content-type' => 'text/plain'}, ['rack missing']]
  else
    data = {counter: counter, script: env['SCRIPT_NAME'], path: env['PATH_INFO'], query: env['QUERY_STRING'], method: env['REQUEST_METHOD'], closed: $closed_bodies || 0}
    [200, {'content-type' => 'application/json'}, [JSON.generate(data)]]
  end
end

use Rack::TempfileReaper
linted = Rack::Lint.new(app)
run lambda { |env|
  if env['PATH_INFO'] == '/io'
    original = env['rack.input'].read
    env['rack.input'].rewind
    raise 'rewind' unless env['rack.input'].read == original
    env['rack.input'].rewind
  end
  env['PATH_INFO'].start_with?('/invalid-') ? app.call(env) : linted.call(env)
}
