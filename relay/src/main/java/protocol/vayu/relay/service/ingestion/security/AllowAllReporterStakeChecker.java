package protocol.vayu.relay.service.ingestion.security;

import org.springframework.stereotype.Component;

@Component
public class AllowAllReporterStakeChecker implements ReporterStakeChecker {

    @Override
    public boolean hasActiveStake(String reporter) {
        return true;
    }
}
