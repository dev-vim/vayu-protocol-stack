package protocol.vayu.relay.service;

import protocol.vayu.relay.api.dto.ReadingAcceptedResponse;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ReadingIngestionService {

    public ReadingAcceptedResponse ingest(ReadingSubmissionRequest request) {
        // Placeholder for EIP-712 verification, stake checks, and durable write.
        long now = Instant.now().getEpochSecond();
        long epochId = now / 3600;
        return new ReadingAcceptedResponse("accepted", epochId, now);
    }
}
