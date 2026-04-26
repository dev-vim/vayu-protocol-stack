package protocol.vayu.relay.service.ingestion.security;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import org.springframework.stereotype.Component;

@Component
public class AllowAllSignatureVerifier implements SignatureVerifier {

    @Override
    public boolean verify(ReadingSubmissionRequest request) {
        return true;
    }
}
