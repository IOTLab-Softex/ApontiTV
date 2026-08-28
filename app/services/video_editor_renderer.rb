require "tmpdir"
require "json"
require "open3"

class VideoEditorRenderer
  class RenderError < StandardError; end

  FFMPEG_CANDIDATE_PATHS = [
    "C:/nginx/ffmpeg/bin/ffmpeg.exe",
    "C:/nginx/ffmpeg/bin/ffmpeg",
    "ffmpeg"
  ].freeze

  IMAGE_CONTENT_TYPES = %w[image/jpeg image/png image/webp image/gif].freeze
  VIDEO_CONTENT_TYPE_PREFIX = "video/".freeze

  def initialize(files:, item_order:, durations:, start_times: nil, captions: nil, caption_layers: nil, caption_positions: nil, caption_x: nil, caption_y: nil, caption_sizes: nil, caption_weights: nil, caption_colors: nil, caption_backgrounds: nil, transitions: nil, transition_durations: nil, timeline_data: nil, project_duration: nil, title:, orientation:)
    @files = files
    @item_order = item_order.to_s
    @durations = durations || {}
    @start_times = start_times || {}
    @captions = captions || {}
    @caption_layers = caption_layers || {}
    @caption_positions = caption_positions || {}
    @caption_x = caption_x || {}
    @caption_y = caption_y || {}
    @caption_sizes = caption_sizes || {}
    @caption_weights = caption_weights || {}
    @caption_colors = caption_colors || {}
    @caption_backgrounds = caption_backgrounds || {}
    @transitions = transitions || {}
    @transition_durations = transition_durations || {}
    @timeline_data = timeline_data
    @timeline_model = parse_timeline_data(timeline_data)
    @project_duration = project_duration.to_f if project_duration.present?
    @title = title.to_s.strip.presence || "video_editor_#{Time.zone.now.strftime('%Y%m%d_%H%M%S')}"
    @orientation = orientation.to_s == "landscape" ? "landscape" : "portrait"
  end

  def render!
    raise RenderError, "FFmpeg nao encontrado. Instale o FFmpeg ou coloque em C:/nginx/ffmpeg/bin." if ffmpeg_executable.blank?

    return render_timeline! if timeline_model.present?

    Dir.mktmpdir("softex-video-editor") do |workspace|
      segments = ordered_files.each_with_index.map do |file, position|
        render_segment(file, position, workspace)
      end

      raise RenderError, "Nao foi possivel preparar os itens da timeline." if segments.blank?

      output_path = File.join(workspace, "#{safe_title}.mp4")
      if real_transitions?
        render_with_transitions!(segments, output_path)
      else
        render_with_concat!(segments, output_path)
      end

      raise RenderError, "O FFmpeg nao gerou o video final." unless File.exist?(output_path)

      File.open(output_path, "rb") do |file|
        ActiveStorage::Blob.create_and_upload!(
          io: file,
          filename: "#{safe_title}.mp4",
          content_type: "video/mp4"
        )
      end
    end
  end

  private

  attr_reader :files, :item_order, :durations, :start_times, :captions, :caption_layers, :caption_positions, :caption_x, :caption_y, :caption_sizes, :caption_weights, :caption_colors, :caption_backgrounds, :transitions, :transition_durations, :timeline_data, :timeline_model, :project_duration, :title, :orientation

  def render_timeline!
    raise RenderError, "Timeline vazia. Adicione pelo menos uma camada." if timeline_clips.blank?

    Dir.mktmpdir("softex-video-editor") do |workspace|
      output_path = File.join(workspace, "#{safe_title}.mp4")
      run_ffmpeg!(*timeline_ffmpeg_arguments(output_path))

      raise RenderError, "O FFmpeg nao gerou o video final." unless File.exist?(output_path)

      File.open(output_path, "rb") do |file|
        ActiveStorage::Blob.create_and_upload!(
          io: file,
          filename: "#{safe_title}.mp4",
          content_type: "video/mp4"
        )
      end
    end
  end

  def timeline_ffmpeg_arguments(output_path)
    width, height = dimensions
    duration = timeline_duration
    media_inputs = []
    next_input_index = 1
    input_arguments = ["-y", "-f", "lavfi", "-i", "color=c=black:s=#{width}x#{height}:r=30:d=#{duration}"]

    timeline_clips.each do |clip|
      next if text_clip?(clip)

      file = files[file_index_for(clip)]
      next if file.blank?

      media_inputs << [clip, next_input_index]
      next_input_index += 1
      if gif_file?(file)
        input_arguments.push("-stream_loop", "-1", "-t", clip_duration(clip).to_s, "-i", file.tempfile.path)
      elsif image_file?(file)
        input_arguments.push("-loop", "1", "-t", clip_duration(clip).to_s, "-i", file.tempfile.path)
      else
        input_arguments.push("-i", file.tempfile.path)
      end
    end

    filter_parts = []
    video_output = timeline_video_filter(filter_parts, media_inputs)
    audio_output = timeline_audio_filter(filter_parts, media_inputs)

    args = input_arguments + ["-filter_complex", filter_parts.join(";"), "-map", video_output]
    args += ["-map", audio_output, "-shortest"] if audio_output.present?
    args += ["-ss", render_range_start.to_s, "-t", render_range_duration.to_s] if render_range_start.positive? || render_range_duration < duration
    args + ["-c:v", "libx264", "-preset", "veryfast", "-r", "30", "-pix_fmt", "yuv420p", "-movflags", "+faststart", output_path]
  end

  def timeline_video_filter(filter_parts, media_inputs)
    filter_parts << "[0:v]format=rgba[vbase0]"
    current_label = "vbase0"
    media_by_clip_id = media_inputs.each_with_object({}) { |(clip, input_index), map| map[clip["id"].to_s] = input_index }
    layer_clips = timeline_clips
      .select { |clip| visual_clip?(clip) || (text_clip?(clip) && clip["text"].to_s.strip.present?) }
      .sort_by { |clip| [-clip_track_index(clip), clip_start(clip)] }

    layer_clips.each_with_index do |clip, index|
      next_label = "vbase#{index + 1}"
      if text_clip?(clip)
        filter_parts << "[#{current_label}]#{timeline_drawtext_filter(clip)}[#{next_label}]"
      else
        input_index = media_by_clip_id[clip["id"].to_s]
        next if input_index.blank?

        clip_label = "vclip#{index}"
        filter_parts << timeline_clip_video_filter(clip, input_index, clip_label)
        filter_parts << "[#{current_label}][#{clip_label}]overlay=x='#{clip_overlay_x_expression(clip)}':y='#{clip_overlay_y(clip)}':eof_action=pass:enable='between(t,#{clip_start(clip)},#{clip_end(clip)})'[#{next_label}]"
      end
      current_label = next_label
    end

    "[#{current_label}]"
  end

  def timeline_clip_video_filter(clip, input_index, clip_label)
    box_width, box_height = clip_box_dimensions(clip)
    duration = clip_duration(clip)
    start = clip_start(clip)
    media_start = clip_media_start(clip)
    fit_filter = "scale=#{box_width}:#{box_height}:force_original_aspect_ratio=decrease,pad=#{box_width}:#{box_height}:(ow-iw)/2:(oh-ih)/2:color=black@0.0,setsar=1,format=rgba"
    base =
      if clip_kind(clip) == "image"
        "[#{input_index}:v]#{fit_filter},trim=duration=#{duration},setpts=PTS-STARTPTS"
      else
        "[#{input_index}:v]trim=start=#{media_start}:duration=#{duration},setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=#{duration},trim=duration=#{duration},#{fit_filter}"
      end

    effect_duration = [clip_effect_duration(clip), duration / 2.0].min
    if clip_effect_in(clip) == "fade" && effect_duration.positive?
      base += ",fade=t=in:st=0:d=#{effect_duration}:alpha=1"
    end
    if clip_effect_out(clip) == "fade" && effect_duration.positive?
      fade_out_start = [duration - effect_duration, 0].max.round(3)
      base += ",fade=t=out:st=#{fade_out_start}:d=#{effect_duration}:alpha=1"
    end

    base += ",setpts=PTS+#{start}/TB"
    "#{base}[#{clip_label}]"
  end

  def timeline_audio_filter(filter_parts, media_inputs)
    audio_inputs = media_inputs.select { |clip, _| audio_clip?(clip) }
    return nil if audio_inputs.blank?

    labels = []
    total = timeline_duration
    audio_inputs.each_with_index do |(clip, input_index), index|
      label = "aclip#{index}"
      delay = (clip_start(clip) * 1000).round
      filter_parts << "[#{input_index}:a]atrim=start=#{clip_media_start(clip)}:duration=#{clip_duration(clip)},asetpts=PTS-STARTPTS,adelay=#{delay}|#{delay},apad,atrim=duration=#{total}[#{label}]"
      labels << "[#{label}]"
    end

    filter_parts << "#{labels.join}amix=inputs=#{labels.length}:duration=longest:dropout_transition=0,atrim=duration=#{total}[aout]"
    "[aout]"
  end

  def timeline_drawtext_filter(clip)
    caption = {
      "text" => clip["text"],
      "position" => "custom",
      "x" => clip["x"].presence || 0.5,
      "y" => clip["y"].presence || 0.82,
      "size" => clip["size"].presence || caption_font_size,
      "weight" => clip["weight"].presence || "bold",
      "color" => clip["color"].presence || "#ffffff",
      "background" => clip["background"].presence || "#000000"
    }

    "#{drawtext_filter(caption)}:enable='between(t,#{clip_start(clip)},#{clip_end(clip)})'"
  end

  def parse_timeline_data(value)
    parsed = JSON.parse(value.to_s)
    parsed.is_a?(Hash) && parsed["clips"].is_a?(Array) ? parsed : nil
  rescue JSON::ParserError, TypeError
    nil
  end

  def timeline_clips
    @timeline_clips ||= Array(timeline_model&.fetch("clips", [])).select do |clip|
      clip.is_a?(Hash) && clip_duration(clip).positive? && !track_disabled?(clip["trackId"])
    end
  end

  def text_clips
    timeline_clips.select { |clip| text_clip?(clip) && clip["text"].to_s.strip.present? }.sort_by { |clip| [clip_track_index(clip), clip_start(clip)] }
  end

  def timeline_duration
    model_duration = timeline_model&.fetch("duration", nil).to_f
    clip_duration = timeline_clips.map { |clip| clip_end(clip) }.max.to_f
    duration = [model_duration, clip_duration, project_duration.to_f].max
    [[duration, 1].max, 3600].min.round(3)
  end

  def render_range_start
    [[timeline_model&.fetch("renderStart", 0).to_f, 0].max, timeline_duration].min.round(3)
  end

  def render_range_end
    raw_end = timeline_model&.fetch("renderEnd", nil).to_f
    raw_end = timeline_duration if raw_end <= 0
    [[raw_end, render_range_start + 0.2].max, timeline_duration].min.round(3)
  end

  def render_range_duration
    [[render_range_end - render_range_start, 0.2].max, timeline_duration].min.round(3)
  end

  def file_index_for(clip)
    Integer(clip["fileIndex"], exception: false).to_i
  end

  def clip_kind(clip)
    clip["kind"].to_s.presence || clip["type"].to_s.presence || "video"
  end

  def clip_start(clip)
    [[clip["start"].to_f, 0].max, 3600].min.round(3)
  end

  def clip_duration(clip)
    [[clip["duration"].to_f, 0].max, 3600].min.round(3)
  end

  def clip_end(clip)
    (clip_start(clip) + clip_duration(clip)).round(3)
  end

  def clip_media_start(clip)
    [[clip["mediaStart"].to_f, 0].max, 86_400].min.round(3)
  end

  def clip_track_index(clip)
    Integer(clip["trackIndex"], exception: false).to_i
  end

  def timeline_tracks
    @timeline_tracks ||= Array(timeline_model&.fetch("tracks", [])).select { |track| track.is_a?(Hash) }
  end

  def track_disabled?(track_id)
    track = timeline_tracks.find { |current| current["id"].to_s == track_id.to_s }
    ActiveModel::Type::Boolean.new.cast(track&.fetch("disabled", false))
  end

  def clip_x(clip)
    [[clip["x"].to_f, 0].max, 1].min
  end

  def clip_y(clip)
    [[clip["y"].to_f, 0].max, 1].min
  end

  def clip_width_ratio(clip)
    raw = clip["width"].presence || 1
    [[raw.to_f, 0.05].max, 1].min
  end

  def clip_height_ratio(clip)
    raw = clip["height"].presence || 1
    [[raw.to_f, 0.05].max, 1].min
  end

  def clip_box_dimensions(clip)
    width, height = dimensions
    [
      [(width * clip_width_ratio(clip)).round, 1].max,
      [(height * clip_height_ratio(clip)).round, 1].max
    ]
  end

  def clip_overlay_x(clip)
    width, = dimensions
    box_width, = clip_box_dimensions(clip)
    [((width - box_width) * clip_x(clip)).round, 0].max
  end

  def clip_overlay_x_expression(clip)
    base = clip_overlay_x(clip)
    box_width, = clip_box_dimensions(clip)
    duration = [clip_effect_duration(clip), clip_duration(clip) / 2.0].min
    return base.to_s unless duration.positive?

    expression = base.to_s
    if clip_effect_in(clip) == "slide"
      in_end = (clip_start(clip) + duration).round(3)
      expression = "if(lt(t\\,#{in_end})\\,#{base}-#{box_width}*(1-(t-#{clip_start(clip)})/#{duration})\\,#{base})"
    end

    if clip_effect_out(clip) == "slide"
      out_start = [clip_end(clip) - duration, clip_start(clip)].max.round(3)
      expression = "if(gt(t\\,#{out_start})\\,#{base}+#{box_width}*((t-#{out_start})/#{duration})\\,#{expression})"
    end

    expression
  end

  def clip_overlay_y(clip)
    _, height = dimensions
    _, box_height = clip_box_dimensions(clip)
    [((height - box_height) * clip_y(clip)).round, 0].max
  end

  def clip_transition(clip)
    clip["transition"].to_s.presence || "cut"
  end

  def clip_transition_duration(clip)
    [[clip["transitionDuration"].to_f, 0].max, 5].min.round(3)
  end

  def clip_effect_in(clip)
    clip["effectIn"].to_s.presence || "none"
  end

  def clip_effect_out(clip)
    clip["effectOut"].to_s.presence || "none"
  end

  def clip_effect_duration(clip)
    [[clip["effectDuration"].to_f, 0].max, 5].min.round(3)
  end

  def visual_clip?(clip)
    %w[video image].include?(clip_kind(clip))
  end

  def audio_clip?(clip)
    clip_kind(clip) == "audio"
  end

  def text_clip?(clip)
    clip_kind(clip) == "text"
  end

  def ordered_files
    indexes = item_order.split(",").filter_map { |value| Integer(value, exception: false) }
    indexes = files.each_index.to_a if indexes.blank?

    indexes.filter_map { |index| files[index] }
  end

  def render_segment(file, position, workspace)
    output_path = File.join(workspace, "segment_#{position.to_s.rjust(3, '0')}.mp4")
    duration = duration_for(position)
    @active_segment_duration = duration
    @active_segment_captions = captions_for(position)

    if gif_file?(file)
      run_ffmpeg!(
        "-y",
        "-stream_loop", "-1",
        "-t", duration.to_s,
        "-i", file.tempfile.path,
        "-vf", video_filter,
        "-an",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-r", "30",
        "-pix_fmt", "yuv420p",
        output_path
      )
    elsif image_file?(file)
      run_ffmpeg!(
        "-y",
        "-loop", "1",
        "-t", duration.to_s,
        "-i", file.tempfile.path,
        "-vf", video_filter,
        "-an",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-r", "30",
        "-pix_fmt", "yuv420p",
        output_path
      )
    elsif video_file?(file)
      run_ffmpeg!(
        "-y",
        "-ss", start_time_for(position).to_s,
        "-stream_loop", "-1",
        "-t", duration.to_s,
        "-i", file.tempfile.path,
        "-vf", video_filter,
        "-an",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-r", "30",
        "-pix_fmt", "yuv420p",
        output_path
      )
    else
      raise RenderError, "Formato nao suportado: #{file.original_filename}"
    end

    raise RenderError, "Falha ao renderizar #{file.original_filename}." unless File.exist?(output_path)

    output_path
  end

  def duration_for(position)
    raw_value = durations[position.to_s] || durations[position] || 8
    value = raw_value.to_f
    value = 8 if value <= 0
    [[value, 1].max, 120].min
  end

  def start_time_for(position)
    raw_value = start_times[position.to_s] || start_times[position] || 0
    value = raw_value.to_f
    [[value, 0].max, 86_400].min
  end

  def transition_duration_for(position)
    raw_value = transition_durations[position.to_s] || transition_durations[position] || 0.5
    value = raw_value.to_f
    [[value, 0.1].max, 5].min
  end

  def render_with_concat!(segments, output_path)
    concat_path = File.join(File.dirname(output_path), "concat.txt")
    temp_output_path = output_path_for_duration(output_path)
    File.write(concat_path, segments.map { |path| "file '#{path.tr("\\", "/").gsub("'", "'\\\\''")}'" }.join("\n"))

    run_ffmpeg!(
      "-y",
      "-f", "concat",
      "-safe", "0",
      "-i", concat_path,
      "-c", "copy",
      "-movflags", "+faststart",
      temp_output_path
    )
    trim_to_project_duration!(temp_output_path, output_path)
  end

  def render_with_transitions!(segments, output_path)
    input_arguments = segments.flat_map { |segment| ["-i", segment] }
    filter = xfade_filter
    last_label = segments.length == 2 ? "[v1]" : "[v#{segments.length - 1}]"
    temp_output_path = output_path_for_duration(output_path)

    run_ffmpeg!(
      "-y",
      *input_arguments,
      "-filter_complex", filter,
      "-map", last_label,
      "-an",
      "-c:v", "libx264",
      "-preset", "veryfast",
      "-pix_fmt", "yuv420p",
      "-movflags", "+faststart",
      temp_output_path
    )
    trim_to_project_duration!(temp_output_path, output_path)
  end

  def xfade_filter
    labels = []
    elapsed_duration = duration_for(0)

    (1...ordered_files.length).each do |position|
      previous_label = position == 1 ? "[0:v]" : "[v#{position - 1}]"
      current_label = "[#{position}:v]"
      output_label = "[v#{position}]"
      transition_position = position - 1
      transition_duration = xfade_duration_for(transition_position)
      offset = [elapsed_duration - transition_duration, 0].max.round(3)
      labels << "#{previous_label}#{current_label}xfade=transition=#{xfade_name_for(transition_position)}:duration=#{transition_duration}:offset=#{offset}#{output_label}"
      elapsed_duration += duration_for(position) - transition_duration
    end

    labels.join(";")
  end

  def real_transitions?
    (0...(ordered_files.length - 1)).any? do |position|
      transition_name_for(position) != "cut"
    end
  end

  def transition_name_for(position)
    transitions[position.to_s].presence || transitions[position].presence || "cut"
  end

  def xfade_name_for(position)
    case transition_name_for(position)
    when "slide" then "slideleft"
    when "blur" then "fade"
    else "fade"
    end
  end

  def xfade_duration_for(position)
    transition_name_for(position) == "cut" ? 0.001 : transition_duration_for(position)
  end

  def dimensions
    orientation == "landscape" ? [1366, 768] : [768, 1366]
  end

  def video_filter
    width, height = dimensions
    base_filter = "scale=#{width}:#{height}:force_original_aspect_ratio=increase,crop=#{width}:#{height},fps=30,format=yuv420p"
    [base_filter, active_segment_effects].compact.join(",")
  end

  def active_segment_effects
    effects = []

    @active_segment_captions.each { |caption| effects << drawtext_filter(caption) }

    effects.join(",").presence
  end

  def drawtext_filter(caption)
    escaped_text = caption.fetch("text", "").to_s.gsub("\\", "\\\\\\\\").gsub(":", "\\:").gsub("'", "\\\\'")
    font_path = caption.fetch("weight", "bold").to_s == "bold" ? "C\\:/Windows/Fonts/arialbd.ttf" : "C\\:/Windows/Fonts/arial.ttf"
    x_expression, y_expression = caption_position_expression(caption)
    "drawtext=fontfile='#{font_path}':text='#{escaped_text}':fontcolor=#{ffmpeg_color(caption.fetch("color", "#ffffff"))}:fontsize=#{caption_size(caption)}:box=1:boxcolor=#{ffmpeg_color(caption.fetch("background", "#000000"))}@0.58:boxborderw=18:x=#{x_expression}:y=#{y_expression}"
  end

  def caption_font_size
    orientation == "landscape" ? 42 : 44
  end

  def caption_size(caption)
    value = caption.fetch("size", caption_font_size).to_i
    [[value, 18].max, 96].min
  end

  def caption_position_expression(caption)
    if caption.fetch("position", nil).to_s == "custom"
      x_value = [[caption.fetch("x", 0.5).to_f, 0].max, 0.95].min
      y_value = [[caption.fetch("y", 0.82).to_f, 0].max, 0.95].min
      return ["(w-text_w)*#{x_value.round(4)}", "(h-text_h)*#{y_value.round(4)}"]
    end

    case caption.fetch("position", "bottom_center").to_s
    when "top_center"
      ["(w-text_w)/2", "70"]
    when "center"
      ["(w-text_w)/2", "(h-text_h)/2"]
    when "bottom_left"
      ["70", "h-text_h-90"]
    when "bottom_right"
      ["w-text_w-70", "h-text_h-90"]
    when "top_left"
      ["70", "70"]
    when "top_right"
      ["w-text_w-70", "70"]
    else
      ["(w-text_w)/2", "h-text_h-90"]
    end
  end

  def captions_for(position)
    from_layers = caption_layers[position.to_s] || caption_layers[position]
    parsed_layers = parse_caption_layers(from_layers)
    return parsed_layers if parsed_layers.present?

    text = captions[position.to_s] || captions[position]
    return [] if text.blank?

    [{
      "text" => text,
      "position" => caption_positions[position.to_s] || caption_positions[position] || "bottom_center",
      "x" => caption_x[position.to_s] || caption_x[position] || 0.5,
      "y" => caption_y[position.to_s] || caption_y[position] || 0.82,
      "size" => caption_sizes[position.to_s] || caption_sizes[position] || caption_font_size,
      "weight" => caption_weights[position.to_s] || caption_weights[position] || "bold",
      "color" => caption_colors[position.to_s] || caption_colors[position] || "#ffffff",
      "background" => caption_backgrounds[position.to_s] || caption_backgrounds[position] || "#000000"
    }]
  end

  def parse_caption_layers(value)
    JSON.parse(value.to_s).filter_map do |layer|
      next if layer["text"].to_s.strip.blank?

      {
        "text" => layer["text"].to_s,
        "position" => layer["position"].presence || "bottom_center",
        "x" => layer["x"].presence || 0.5,
        "y" => layer["y"].presence || 0.82,
        "size" => layer["size"].presence || caption_font_size,
        "weight" => layer["weight"].presence || "bold",
        "color" => layer["color"].presence || "#ffffff",
        "background" => layer["background"].presence || "#000000"
      }
    end
  rescue JSON::ParserError
    []
  end

  def output_path_for_duration(output_path)
    valid_project_duration? ? output_path.sub(/\.mp4\z/, "_full.mp4") : output_path
  end

  def trim_to_project_duration!(input_path, output_path)
    return unless valid_project_duration?

    run_ffmpeg!(
      "-y",
      "-i", input_path,
      "-t", project_duration.to_s,
      "-c", "copy",
      "-movflags", "+faststart",
      output_path
    )
  end

  def valid_project_duration?
    project_duration.present? && project_duration.positive?
  end

  def ffmpeg_color(value)
    value.to_s.match?(/\A#[0-9a-fA-F]{6}\z/) ? "0x#{value.delete('#')}" : "white"
  end

  def image_file?(file)
    IMAGE_CONTENT_TYPES.include?(file.content_type.to_s)
  end

  def gif_file?(file)
    file.content_type.to_s == "image/gif" || file.original_filename.to_s.downcase.end_with?(".gif")
  end

  def video_file?(file)
    file.content_type.to_s.start_with?(VIDEO_CONTENT_TYPE_PREFIX)
  end

  def safe_title
    title.parameterize.presence || "video-editor"
  end

  def run_ffmpeg!(*arguments)
    _stdout, stderr, status = Open3.capture3(ffmpeg_executable, *arguments)
    return if status.success?

    detail = stderr.to_s.lines.last(8).join.strip
    message = "O FFmpeg encontrou um erro ao gerar o video."
    message += " #{detail}" if detail.present?
    raise RenderError, message
  end

  def ffmpeg_executable
    @ffmpeg_executable ||= FFMPEG_CANDIDATE_PATHS.find do |path|
      if path.include?("/") || path.include?("\\")
        File.exist?(File.expand_path(path))
      else
        system("where", path, out: File::NULL, err: File::NULL)
      end
    end
  end
end
