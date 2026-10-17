package modules.bxjrubyrack;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Cooperatively cancels streaming I/O while allowing already active finite responses to drain. */
final class ManagedSink implements RackSink, AutoCloseable {
    private final RackSink delegate;
    private final BooleanSupplier draining;
    private final AtomicInteger streams;
    private boolean streaming;
    private volatile boolean closed;

    /**
     * Wraps a transport with its owning app's lifecycle.
     * @param delegate the byte transport
     * @param draining whether the app is draining
     * @param streams the app's active stream counter
     */
    ManagedSink(RackSink delegate, BooleanSupplier draining, AtomicInteger streams) {
        this.delegate = delegate;
        this.draining = draining;
        this.streams = streams;
    }

    /**
     * Sets the validated response status.
     * @param status the response status
     */
    public void status(int status) {
        delegate.status(status);
    }

    /**
     * Adds one validated header value.
     * @param name the lowercase header name
     * @param value the header value
     */
    public void header(String name, String value) {
        delegate.header(name, value);
    }

    /** Marks this request as a stream exactly once. */
    public void startStreaming() {
        if (!streaming) {
            streaming = true;
            streams.incrementAndGet();
            delegate.startStreaming();
        }
    }

    /** @return whether this stream has completed or its app is draining */
    public boolean isClosed() {
        return closed || (streaming && draining.getAsBoolean()) || delegate.isClosed();
    }

    /**
     * Rejects further I/O after cooperative cancellation.
     * @throws IOException if the stream is closed
     */
    private void checkOpen() throws IOException {
        if (isClosed()) {
            throw new IOException("Rack stream is closed or its app is draining");
        }
    }

    /**
     * Writes one binary chunk with transport backpressure.
     * @param bytes the binary chunk
     * @throws IOException on cancellation or transport failure
     */
    public void write(byte[] bytes) throws IOException {
        checkOpen();
        delegate.write(bytes);
    }

    /** @throws IOException on cancellation or transport failure */
    public void flush() throws IOException {
        checkOpen();
        delegate.flush();
    }

    /** @throws IOException on cancellation or transport failure */
    public void finish() throws IOException {
        checkOpen();
        delegate.finish();
    }

    /** @return successfully written bytes */
    public long bytes() {
        return delegate.bytes();
    }

    /** Releases the stream counter exactly once without touching Ruby from another thread. */
    public void close() {
        if (!closed) {
            closed = true;
            if (streaming) {
                streams.decrementAndGet();
            }
        }
    }
}
