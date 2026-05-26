package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

/**
 * Per-epoch ingress state: reading queue + replay-dedup set.
 *
 * <p>Ingestion follows a two-phase protocol to ensure that only fully-validated
 * readings reach the commit queue:
 * <ol>
 *   <li>Call {@link #tryClaimReplayKey} — atomically reserves the key.  Returns
 *       {@code false} immediately if the key was already seen (duplicate).</li>
 *   <li>Run any remaining validations (rate-limit, stake check, …).</li>
 *   <li>On success call {@link EpochReadingStore#enqueue} to add the reading to
 *       the commit queue.</li>
 *   <li>On failure call {@link #releaseReplayKey} so the reporter can retry.</li>
 * </ol>
 *
 * <p>{@link #enqueueIfNotSeen} is provided as a convenience default that combines
 * steps 1 and 3; it is suitable for tests and other callers that do not need the
 * intermediate validation window.
 *
 * <p>{@link EpochReadingStore#drainEpoch} clears both the reading queue and the
 * seen-key set for the given epoch in a single call.
 */
public interface EpochIngressWindow extends EpochReadingStore {

    /**
     * Atomically claims {@code replayKey} for {@code epochId} without enqueuing
     * the reading.  Exactly one concurrent caller will receive {@code true} for a
     * given key; all others receive {@code false} (duplicate).
     *
     * @return {@code true} if the key was freshly claimed; {@code false} if it was
     *         already seen
     */
    boolean tryClaimReplayKey(long epochId, String replayKey);

    /**
     * Releases a previously claimed {@code replayKey}, allowing a future submission
     * with the same key to be accepted.  Must be called when a reading is rejected
     * after a successful {@link #tryClaimReplayKey} (e.g. rate-limited, no stake).
     */
    void releaseReplayKey(long epochId, String replayKey);

    /**
     * Convenience: claims {@code replayKey} and, if successful, immediately enqueues
     * {@code request}.  Equivalent to {@code tryClaimReplayKey} followed by
     * {@link EpochReadingStore#enqueue} with no intermediate validation window.
     *
     * @return {@code true} if the reading was accepted; {@code false} if duplicate
     */
    default boolean enqueueIfNotSeen(ReadingSubmissionRequest request, String replayKey) {
        if (!tryClaimReplayKey(request.epochId(), replayKey)) {
            return false;
        }
        enqueue(request);
        return true;
    }
}
