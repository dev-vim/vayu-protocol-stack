package protocol.vayu.relay.service.ingestion.security;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;

public interface SignatureVerifier {

    boolean verify(ReadingSubmissionRequest request);
}
