require 'stringio'

module Kernel
  def exit!(*)
    raise 'exit! is unsupported in embedded Rack apps'
  end

  module_function :exit!
end

require 'bundler/setup' if bx_boot['bundler']
require 'rack'
raise "Unsupported Rack #{Rack.release}; Rack 3.x is required" unless Rack.release.split('.').first == '3'

module BxRack
  # Normalize JRuby StringIO's optional read-buffer encoding to byte IO semantics.
  class Input
    def initialize(io)
      @io = io
    end

    def read(*args)
      result = @io.read(*args)
      args[1].force_encoding(Encoding::BINARY) if args.size == 2
      result&.force_encoding(Encoding::BINARY)
    end

    def gets
      @io.gets&.force_encoding(Encoding::BINARY)
    end

    def each
      return enum_for(:each) unless block_given?
      while (line = gets)
        yield line
      end

      self
    end

    def rewind
      @io.rewind
    end

    def close
      @io.close
    end

    def closed?
      @io.closed?
    end

    def external_encoding
      Encoding::BINARY
    end

    def binmode?
      true
    end
  end

  def self.boot(path)
    Rack::Builder.parse_file(path)
  end

  def self.dispatch(app, metadata, input, sink)
    env = metadata.transform_values { |value| value.b }
    stream = Input.new(input.get_path ? File.open(input.get_path, 'rb') : StringIO.new(String.from_java_bytes(input.get_bytes).b))
    env['rack.input'] = stream
    env['rack.errors'] = $stderr
    env['rack.response_finished'] = []
    body = status = headers = error = nil
    begin
      tuple = app.call(env)
      body = tuple[2] if tuple.is_a?(Array) && tuple.size == 3
      raise 'Rack response must be a mutable three-element Array' unless tuple.is_a?(Array) && !tuple.frozen? && tuple.size == 3
      status, headers, body = tuple
      raise 'Unsupported HTTP status (synchronous responses require 200..599)' unless status.is_a?(Integer) && (200..599).cover?(status)
      raise 'Rack headers must be a mutable Hash' unless headers.is_a?(Hash) && !headers.frozen?
      raise 'Callable/streaming Rack bodies are unsupported; an enumerable body is required' unless body.respond_to?(:each)
      headers.each do |key, value|
        raise 'Invalid or unsupported Rack response header' unless key.is_a?(String) && key.match?(/\A[!#$%&'*+.^_`|~0-9a-z-]+\z/) && key != 'status' && !key.start_with?('rack.')
        raise 'Transport-owned response header is unsupported' if %w[connection transfer-encoding upgrade keep-alive trailer].include?(key)
        values = value.is_a?(Array) ? value : [value]
        raise 'Invalid response header value' unless values.all? { |v| v.is_a?(String) && !v.match?(/[\x00-\x1f\x7f]/) }
        if key == 'content-length'
          raise 'Invalid content-length' unless value.is_a?(String) && value.match?(/\A[0-9]+\z/)
        end
      end
      if [204, 304].include?(status)
        raise 'Bodyless status cannot contain content-type or content-length' if headers.key?('content-type') || headers.key?('content-length')
      end
      sink.status(status)
      headers.each do |key, value|
        (value.is_a?(Array) ? value : [value]).each do |header_value|
          sink.header(key, header_value)
        end
      end

      count = 0
      no_body = env['REQUEST_METHOD'] == 'HEAD' || [204, 304].include?(status)
      body.each do |chunk|
        raise 'Rack body chunks must be Strings' unless chunk.is_a?(String)
        unless no_body
          count += chunk.bytesize
          raise 'Response exceeds content-length' if headers['content-length'] && count > headers['content-length'].to_i
          sink.write(chunk.to_java_bytes)
        end
      end unless no_body
      raise 'Response shorter than content-length' if !no_body && headers['content-length'] && count != headers['content-length'].to_i
      sink.finish
    rescue SystemExit => caught
      error = caught
      raise "Embedded Rack app attempted to exit with status #{caught.status}"
    rescue Exception => caught
      error = caught
      raise
    ensure
      begin
        body.close if body.respond_to?(:close)
      rescue Exception => close_error
        if error.nil?
          error = close_error
          raise
        end
        warn "Rack body close failed: #{close_error.class}: #{close_error.message}"
      ensure
        begin
          env['rack.response_finished'].reverse_each do |callback|
            begin
              callback.call(env, status, headers, error)
            rescue Exception => callback_error
              warn "Rack response_finished failed: #{callback_error.class}: #{callback_error.message}"
            end
          end
        ensure
          stream.close unless stream.closed?
        end
      end
    end
    nil
  end
end

begin
  bx_app = BxRack.boot(bx_boot['rackup'])
rescue SystemExit => error
  raise "Embedded Rack boot attempted to exit with status #{error.status}"
end

{ 'rack' => Rack.release, 'ruby' => RUBY_VERSION, 'jruby' => JRUBY_VERSION }
