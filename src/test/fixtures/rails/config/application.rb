require 'rails'
require 'action_controller/railtie'
require 'active_record/railtie'
Bundler.require(*Rails.groups)
module RackFixture
  class Application < Rails::Application
    config.load_defaults 7.2
    config.eager_load = false
    config.secret_key_base = 'fixture-only-not-a-production-secret-' * 4
    config.hosts = ['127.0.0.1', 'localhost']
    config.logger = Logger.new($stderr)
    config.log_level = :warn
    config.active_record.schema_format = :ruby
    config.active_record.dump_schema_after_migration = false
    config.action_controller.allow_forgery_protection = true
    config.public_file_server.enabled = true
    config.session_store :cookie_store, key: '_bx_rack_fixture', same_site: :lax
  end
end
