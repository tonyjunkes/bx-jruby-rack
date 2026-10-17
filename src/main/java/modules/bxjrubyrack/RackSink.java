package modules.bxjrubyrack;

import java.io.IOException;

/** Transport-neutral response contract invoked by the Ruby bridge. */
public interface RackSink {
    /**
     * Sets the validated HTTP status before writing body bytes.
     *
     * @param status the validated HTTP response status
     */
    void status(int status);

    /**
     * Adds a validated header value, preserving repeated values such as cookies.
     *
     * @param name the validated lowercase HTTP header name
     * @param value one validated header value
     */
    void header(String name, String value);

    /**
     * Writes raw body bytes using the host transport's backpressure.
     *
     * @param bytes the raw binary body chunk
     * @throws IOException if the host transport cannot write the chunk
     */
    void write(byte[] bytes) throws IOException;

    /** Marks output as streaming so application drain may cooperatively stop subsequent I/O. */
    default void startStreaming() {}

    /**
     * Flushes partial output without ending the response.
     * @throws IOException if output cannot be flushed
     */
    default void flush() throws IOException {
        finish();
    }

    /**
     * Reports whether streaming output has been closed or cancelled by drain.
     * @return true when further stream I/O must fail
     */
    default boolean isClosed() {
        return false;
    }

    /**
     * Flushes the completed finite response to the client.
     *
     * @throws IOException if the host transport cannot flush the completed response
     */
    void finish() throws IOException;

    /**
     * Reports the number of body bytes successfully written.
     *
     * @return the cumulative byte count
     */
    long bytes();
}
