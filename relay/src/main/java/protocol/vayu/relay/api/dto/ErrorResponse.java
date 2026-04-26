package protocol.vayu.relay.api.dto;

public record ErrorResponse(String error, String message, Integer retryAfter) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null);
    }
}
