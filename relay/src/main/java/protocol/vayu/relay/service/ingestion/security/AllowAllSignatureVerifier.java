package protocol.vayu.relay.service.ingestion.security;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "relay.security",
    name = "signature-verification-enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class AllowAllSignatureVerifier implements SignatureVerifier {

    @Override
    public boolean verify(ReadingSubmissionRequest request) {
        return true;
    }
}
