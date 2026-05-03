package protocol.vayu.relay.service.commit;

/**
 * Pins an epoch blob to IPFS and returns its CID.
 *
 * Two implementations are provided:
 *   - {@link KuboIpfsPinClient}   — local Kubo node (dev / CI)
 *   - {@link PinataIpfsPinClient} — managed Pinata service (production)
 *
 * The active implementation is selected via {@code relay.ipfs.provider}.
 */
public interface IpfsPinClient {

    /**
     * Pins the given JSON blob to IPFS.
     *
     * @param epochId  Epoch identifier, used for naming the pinned object.
     * @param jsonBlob Serialised epoch blob as a UTF-8 JSON string.
     * @return IPFS CID (v0 "Qm..." or v1 "bafy...").
     * @throws IpfsPinException if the pin operation fails.
     */
    String pin(long epochId, String jsonBlob);
}
