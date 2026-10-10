class CreateNotes < ActiveRecord::Migration[7.2]
  def change
    create_table :notes, if_not_exists: true do |table|
      table.string :text, null: false
    end
  end
end
