package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * One part of a message whose {@code content} was sent as an array (v4 B.1).
 *
 * <p>Only messages that carry a non-text part keep their parts: a text-only array
 * is flattened by the ingress into the message's text, so it behaves exactly like
 * the string form. Non-text parts cannot be served by the advisor chain and mark
 * the request for pass-through ({@link PassThroughFeature}).
 */
public sealed interface ContentPart {

  /** A {@code text} part. */
  record Text(String text) implements ContentPart {
  }

  /** An {@code image_url} part: a URL or a {@code data:} URI, and the optional detail level. */
  record ImageUrl(String url, String detail) implements ContentPart {
  }

  /** An {@code input_audio} part: base64 audio and its format ({@code wav}, {@code mp3}…). */
  record InputAudio(String data, String format) implements ContentPart {
  }

  /** A {@code file} part: an uploaded file id, or inline base64 data and its name. */
  record File(String fileId, String filename, String fileData) implements ContentPart {
  }

  /**
   * A part of a type this gateway does not know, preserved as the client sent it.
   *
   * @param type the part's {@code type}
   * @param json the whole part, serialised by the ingress
   */
  record Other(String type, String json) implements ContentPart {
  }
}
