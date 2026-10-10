package modules.bxjrubyrack;

import ortus.boxlang.runtime.types.exceptions.BoxRuntimeException;

public final class RackException extends BoxRuntimeException {
    private final String code;
    private final int status;

    /**
     * Creates a diagnostic failure with a stable code and HTTP status; optional causes preserve internal
     * diagnostics.
     *
     * @param code the stable diagnostic error code
     * @param status the validated HTTP response status
     * @param message the internal diagnostic message
     */
    public RackException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * Creates a diagnostic failure with a stable code and HTTP status; optional causes preserve internal
     * diagnostics.
     *
     * @param code the stable diagnostic error code
     * @param status the validated HTTP response status
     * @param message the internal diagnostic message
     * @param cause the original failure preserved for diagnostics
     */
    public RackException(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    /**
     * Returns the stable diagnostic code for error classification.
     *
     * @return the Rack error code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the status used when an HTTP response remains uncommitted.
     *
     * @return the HTTP error status
     */
    public int getStatus() {
        return status;
    }
}
