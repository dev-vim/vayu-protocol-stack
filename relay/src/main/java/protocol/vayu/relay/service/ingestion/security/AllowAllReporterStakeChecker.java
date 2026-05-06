package protocol.vayu.relay.service.ingestion.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "relay.security",
        name = "stake-check-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class AllowAllReporterStakeChecker implements ReporterStakeChecker {

    @Override
    public boolean hasActiveStake(String reporter) {
        return true;
    }
}
