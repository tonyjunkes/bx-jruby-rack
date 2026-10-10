# Explicit development/CI preparation only. Never loaded by the module.
require 'rubygems'
require 'bundler'
root, destination = ARGV
ENV['BUNDLE_GEMFILE'] = File.expand_path('Gemfile', root)
ENV['BUNDLE_PATH'] = File.expand_path(destination)
ENV['BUNDLE_FROZEN'] = File.exist?(ENV['BUNDLE_GEMFILE'] + '.lock') && ARGV[2] != 'update' ? 'true' : 'false'
Bundler.reset!
require 'bundler/cli'
Bundler::CLI.start(['install'])
