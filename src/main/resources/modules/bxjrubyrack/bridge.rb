require 'stringio'

module Kernel
  def exit!(*)
    raise 'exit! is unsupported in embedded Rack apps'
  end

  module_function :exit!
end

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

  # Buffered request input and raw response output; no socket hijack is exposed.
  class Stream
    def initialize(input, sink, length, autoflush = false)
      @input, @sink, @length, @autoflush = input, sink, length, autoflush
      @count = 0
      @closed = @read_closed = @write_closed = false
    end

    attr_reader :count

    def write(*chunks)
      raise IOError, 'closed stream for writing' if @write_closed || closed?
      written = 0
      chunks.each do |chunk|
        raise TypeError, 'Rack body chunks must be Strings' unless chunk.is_a?(String)
        raise IOError, 'response exceeds content-length' if @length && @count + chunk.bytesize > @length
        @sink.write(chunk.to_java_bytes)
        @count += chunk.bytesize
        written += chunk.bytesize
        @sink.flush if @autoflush
      end
      written
    end

    def <<(chunk)
      write(chunk)
      self
    end

    def read(*args)
      raise IOError, 'closed stream' if @read_closed || closed?
      @input.read(*args)
    end

    def flush
      raise IOError, 'closed stream for writing' if @write_closed || closed?
      @sink.flush
      self
    end

    def close_read
      @read_closed = true
      @closed = true if @write_closed
      nil
    end

    def close_write
      return if @write_closed
      begin
        @sink.flush unless @sink.is_closed
      ensure
        @write_closed = true
        @closed = true if @read_closed
      end
      nil
    end

    def close
      return if @closed
      begin
        close_write
      ensure
        @closed = @read_closed = true
      end
      nil
    end

    def closed?
      @closed || @sink.is_closed
    end

    # Invalidate retained stream objects before body cleanup or worker reuse.
    def server_close
      @closed = @read_closed = @write_closed = true
      @input = @sink = nil
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
      raise 'Rack body must respond to each or call' unless body.respond_to?(:each) || body.respond_to?(:call)
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

      no_body = env['REQUEST_METHOD'] == 'HEAD' || [204, 304].include?(status)
      sse = Array(headers['content-type']).any? do |value|
        value.split(';', 2).first.to_s.strip.casecmp?('text/event-stream')
      end
      callable = !body.respond_to?(:each)
      sink.start_streaming if !no_body && (callable || sse)
      output = Stream.new(stream, sink, headers['content-length']&.to_i, sse)
      unless no_body
        if callable
          body.call(output)
        else
          body.each { |chunk| output.write(chunk) }
        end
        raise 'Response shorter than content-length' if headers['content-length'] && output.count != headers['content-length'].to_i
      end
      sink.finish
    rescue SystemExit => caught
      error = caught
      raise "Embedded Rack app attempted to exit with status #{caught.status}"
    rescue Exception => caught
      error = caught
      raise
    ensure
      begin
        output&.server_close
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
