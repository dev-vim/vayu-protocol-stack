package protocol.vayu.relay.service.commit;

/**
 * Thrown when an on-chain epoch commit transaction fails — either during
 * gas estimation, transaction submission, or when the node rejects the tx.
 */
public class EpochCommitException extends RuntimeException {

    public EpochCommitException(String message) {
        super(message);
    }

    public EpochCommitException(String message, Throwable cause) {
        super(message, cause);
    }
}
