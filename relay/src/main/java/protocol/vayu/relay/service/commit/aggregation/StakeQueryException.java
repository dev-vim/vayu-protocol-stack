package protocol.vayu.relay.service.commit.aggregation;

/**
 * Thrown when an on-chain stake query via {@code eth_call} fails.
 */
public class StakeQueryException extends RuntimeException {

    public StakeQueryException(String message) {
        super(message);
    }

    public StakeQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
