package protocol.vayu.relay.api.dto;

public record ReadingAcceptedResponse(String status, long epochId, long receivedAt) {
}
