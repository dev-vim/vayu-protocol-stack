package protocol.vayu.relay.api.error;

import org.springframework.http.HttpStatus;

public class RelayApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Integer retryAfter;

    public RelayApiException(HttpStatus status, String errorCode, String message, Integer retryAfter) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public static RelayApiException badRequest(String message) {
        return new RelayApiException(HttpStatus.BAD_REQUEST, "invalid_request", message, null);
    }

    public static RelayApiException rateLimited(String message, int retryAfter) {
        return new RelayApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message, retryAfter);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public Integer retryAfter() {
        return retryAfter;
    }
}
