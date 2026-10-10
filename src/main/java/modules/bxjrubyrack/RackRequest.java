package modules.bxjrubyrack;

import java.io.InputStream;
import java.util.Map;

/**
 * Transport-neutral request passed to an admitted Ruby worker.
 *
 * @param input the raw body stream, read only after admission
 * @param contentLength the declared body length, negative when unknown
 * @param metadata the fresh CGI fields for this request
 * @param sink the host's binary response transport
 */
record RackRequest(InputStream input, long contentLength, Map<String, String> metadata, RackSink sink) {}
