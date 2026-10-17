package modules.bxjrubyrack;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Raw byte sink, never BoxLang's page writer. */
public final class RackResponse implements RackSink {
    private final HttpServletResponse response;
    private long bytes;

    /**
     * Wraps the servlet byte output stream without using BoxLang's page buffer.
     *
     * @param response the servlet response owned by this Rack request
     */
    public RackResponse(HttpServletResponse response) {
        this.response = response;
    }

    /**
     * Sets the servlet status before the body is committed.
     *
     * @param value the validated HTTP status
     */
    public void status(int value) {
        response.setStatus(value);
    }

    /**
     * Adds a header value without replacing existing values.
     *
     * @param name the validated lowercase HTTP header name
     * @param value one validated header value
     */
    public void header(String name, String value) {
        response.addHeader(name, value);
    }

    /**
     * Writes a binary chunk and counts bytes only after a successful write.
     *
     * @param bytes the raw binary body chunk
     * @throws IOException if the servlet output stream cannot write the chunk
     */
    public void write(byte[] bytes) throws IOException {
        response.getOutputStream().write(bytes);
        this.bytes += bytes.length;
    }

    /**
     * Flushes the servlet response and commits pending headers and bytes.
     *
     * @throws IOException if the servlet response cannot flush its output buffer
     */
    public void finish() throws IOException {
        response.flushBuffer();
    }

    /** Flushes partial bytes and commits headers without ending the servlet response.
     * @throws IOException if the client disconnects or flushing fails
     */
    public void flush() throws IOException {
        response.flushBuffer();
    }

    /**
     * Reports successfully written body bytes.
     *
     * @return the cumulative byte count
     */
    public long bytes() {
        return bytes;
    }
}
