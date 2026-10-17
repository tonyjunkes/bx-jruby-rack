module Kernel
    def exit!(*)
        raise 'exit! is unsupported in embedded Rack tasks'
    end
    module_function :exit!
end
begin
    require 'rake'
    Rake.application.init('rake', [])
    load File.join(Dir.pwd, 'Rakefile')
    Rake.application.load_imports
    Rake::Task[bx_task].invoke(*bx_args)
    nil
rescue SystemExit => error
    raise "Embedded task attempted to exit with status #{error.status}"
end
