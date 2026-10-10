class NotesController < ActionController::Base
  def health
    session[:visits] = (session[:visits] || 0) + 1
    render json: {rails: Rails.version, mount: request.script_name, visits: session[:visits]}
  end

  def create
    note = Note.create!(text: params.require(:text))
    render json: {id: note.id, text: note.text, url: url_for(action: :show, id: note.id, only_path: true)}, status: :created
  end

  def show
    render json: Note.find(params[:id]).as_json(only: [:id, :text])
  end
end
