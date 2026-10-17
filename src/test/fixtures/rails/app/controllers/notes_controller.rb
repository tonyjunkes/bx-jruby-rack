class NotesController < ActionController::Base
  protect_from_forgery with: :exception

  def index
    @notes = Note.order(:id).limit(20)
  end

  def form_create
    note = Note.create!(text: params.require(:text))
    redirect_to action: :index, status: :see_other
  end

  def upload
    render plain: params.require(:upload).read, content_type: 'application/octet-stream'
  end

  def health
    session[:visits] = (session[:visits] || 0) + 1
    render json: {rails: Rails.version, mount: request.script_name, visits: session[:visits], csrf: form_authenticity_token}
  end

  def create
    note = Note.create!(text: params.require(:text))
    render json: {id: note.id, text: note.text, url: url_for(action: :show, id: note.id, only_path: true)}, status: :created
  end

  def show
    render json: Note.find(params[:id]).as_json(only: [:id, :text])
  end
end
