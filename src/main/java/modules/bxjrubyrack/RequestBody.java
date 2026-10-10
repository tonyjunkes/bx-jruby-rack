package modules.bxjrubyrack;

import java.io.*;
import java.nio.file.*;

/** Bounded input storage. Created only after a worker owns the request. */
public final class RequestBody implements AutoCloseable {
    private byte[] memory;
    private Path file;
    private long length;

    /**
     * Creates an empty body holder for admitted request preparation.
     */
    private RequestBody() {}

    /**
     * Reads an admitted request within configured bounds, spilling to a private temporary file above the
     * memory threshold. Failed reads remove partial spill files.
     *
     * @param input the raw request input stream
     * @param declared the declared content length, negative when unknown
     * @param config the application configuration
     * @return the owned body, which must be closed after response cleanup
     * @throws IOException if the request stream or temporary body storage fails
     * @throws RackException if the actual or declared body exceeds its configured limit
     */
    static RequestBody read(InputStream input, long declared, RackConfig config) throws IOException {
        if (declared > config.maxRequestBodyBytes())
            throw new RackException("BODY_TOO_LARGE", 413, "Request body too large");
        var result = new RequestBody();
        var memory = new ByteArrayOutputStream();
        OutputStream output = memory;
        try {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int count; (count = input.read(buffer)) != -1; ) {
                total += count;
                if (total > config.maxRequestBodyBytes())
                    throw new RackException("BODY_TOO_LARGE", 413, "Request body too large");
                if (result.file == null && total > config.requestMemoryThresholdBytes()) {
                    result.file = Files.createTempFile("bx-jruby-rack-", ".body");
                    output = Files.newOutputStream(result.file);
                    memory.writeTo(output);
                }
                output.write(buffer, 0, count);
            }
            output.close();
            result.length = total;
            if (result.file == null) result.memory = memory.toByteArray();
            return result;
        } catch (Throwable e) {
            try {
                output.close();
            } catch (Throwable cleanup) {
                e.addSuppressed(cleanup);
            }
            try {
                result.close();
            } catch (Throwable cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Exposes in-memory body bytes to the Ruby input bridge.
     *
     * @return the byte buffer, or null for a spooled body
     */
    public byte[] getBytes() {
        return memory;
    }

    /**
     * Reports the actual bytes read from the request.
     *
     * @return the captured body length in bytes
     */
    public long length() {
        return length;
    }

    /**
     * Exposes a spill-file path to the Ruby input bridge.
     *
     * @return the temporary file path, or null for an in-memory body
     */
    public String getPath() {
        return file == null ? null : file.toString();
    }

    /**
     * Deletes the owned spill file; repeated calls are harmless.
     *
     * @throws IOException if the owned spill file cannot be deleted
     */
    @Override
    public void close() throws IOException {
        if (file != null) Files.deleteIfExists(file);
        memory = null;
    }
}
