package protocol.vayu.relay.service.ingestion.security;

public interface ReporterStakeChecker {

    boolean hasActiveStake(String reporter);
}
