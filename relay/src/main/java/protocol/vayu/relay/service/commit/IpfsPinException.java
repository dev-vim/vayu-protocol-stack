package protocol.vayu.relay.service.commit;

/**
 * Thrown when an IPFS pin operation fails — HTTP error, unexpected response shape,
 * or parse failure.
 */
public class IpfsPinException extends RuntimeException {

    public IpfsPinException(String message) {
        super(message);
    }

    public IpfsPinException(String message, Throwable cause) {
        super(message, cause);
    }
}
