package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

/**
 * Per-epoch ingress state: reading queue + replay-dedup set.
 *
 * <p>{@link #enqueueIfNotSeen} is the primary ingestion entry point. It atomically
 * checks whether the given replayKey has already been accepted for this epoch,
 * and only enqueues if not. The replayKey should encode the minimal tuple that
 * uniquely identifies a reading within an epoch — typically
 * {@code normalizedReporter:epochId:h3Index}. Using the EIP-712 digest as the
 * replayKey also covers signature-malleability replays, since both {@code (r,s)}
 * and {@code (r,n-s)} produce the same digest.
 *
 * <p>{@link #drainEpoch} clears both the reading queue and the seen-key set for
 * the given epoch in a single call, so there is no separate lifecycle management
 * required.
 */
public interface EpochIngressWindow extends EpochReadingStore {

    /**
     * Accepts and enqueues {@code request} for its epoch if {@code replayKey} has
     * not been seen before in that epoch.
     *
     * @return {@code true} if the reading was accepted; {@code false} if it was
     *         rejected as a duplicate
     */
    boolean enqueueIfNotSeen(ReadingSubmissionRequest request, String replayKey);
}
