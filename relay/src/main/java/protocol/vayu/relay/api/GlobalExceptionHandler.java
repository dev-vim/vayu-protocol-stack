package protocol.vayu.relay.api;

import protocol.vayu.relay.api.dto.ErrorResponse;
import protocol.vayu.relay.api.error.RelayApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_request", message));
    }

        @ExceptionHandler(RelayApiException.class)
        public ResponseEntity<ErrorResponse> handleRelayApiError(RelayApiException ex) {
            return ResponseEntity.status(Objects.requireNonNull(ex.status()))
            .body(new ErrorResponse(ex.errorCode(), ex.getMessage(), ex.retryAfter()));
        }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "unexpected relay error"));
    }
}
