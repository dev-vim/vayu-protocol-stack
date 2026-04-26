package protocol.vayu.relay.api;

import jakarta.validation.Valid;
import protocol.vayu.relay.api.dto.HealthStatusResponse;
import protocol.vayu.relay.api.dto.ReadingAcceptedResponse;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.service.ReadingIngestionService;
import protocol.vayu.relay.service.RelayStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RelayController {

    private final ReadingIngestionService readingIngestionService;
    private final RelayStatusService relayStatusService;

    public RelayController(ReadingIngestionService readingIngestionService, RelayStatusService relayStatusService) {
        this.readingIngestionService = readingIngestionService;
        this.relayStatusService = relayStatusService;
    }

    @PostMapping("/readings")
    public ResponseEntity<ReadingAcceptedResponse> submitReading(@Valid @RequestBody ReadingSubmissionRequest request) {
        return ResponseEntity.ok(readingIngestionService.ingest(request));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthStatusResponse> health() {
        HealthStatusResponse status = relayStatusService.getStatus();
        HttpStatus code = "healthy".equals(status.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(status);
    }
}
