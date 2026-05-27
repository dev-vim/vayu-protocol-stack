package protocol.vayu.relay.service.commit;

import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.config.RelayProperties;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregate;
import protocol.vayu.relay.service.commit.aggregation.EpochAggregator;
import protocol.vayu.relay.service.commit.EpochIngressWindow;
import protocol.vayu.relay.service.RelayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class EpochCommitCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(EpochCommitCoordinator.class);

    private final RelayProperties relayProperties;
    private final EpochIngressWindow epochIngressWindow;
    private final EpochAggregator epochAggregator;
    private final EpochCommitPublisher epochCommitPublisher;
    private final CommitCycleState commitCycleState;
    private final RelayMetrics relayMetrics;

    public EpochCommitCoordinator(
            RelayProperties relayProperties,
            EpochIngressWindow epochIngressWindow,
            EpochAggregator epochAggregator,
            EpochCommitPublisher epochCommitPublisher,
            CommitCycleState commitCycleState,
            RelayMetrics relayMetrics
    ) {
        this.relayProperties = relayProperties;
        this.epochIngressWindow = epochIngressWindow;
        this.epochAggregator = epochAggregator;
        this.epochCommitPublisher = epochCommitPublisher;
        this.commitCycleState = commitCycleState;
        this.relayMetrics = relayMetrics;
    }

    @Scheduled(fixedDelayString = "${relay.epoch.commit-check-interval-ms}")
    public void runCommitCycle() {
        long now = Instant.now().getEpochSecond();
        commitCycleState.markWorkerHeartbeat(now);

        long epochDuration = Math.max(1, relayProperties.epoch().durationSeconds());
        long latestSealableEpoch = (now / epochDuration) - 1;
        if (latestSealableEpoch < 0) {
            return;
        }

        long startEpoch = commitCycleState.lastCommittedEpoch() < 0
                ? latestSealableEpoch
                : commitCycleState.lastCommittedEpoch() + 1;

        LOG.info("starting epoch commit cycle: now={}, latestSealableEpoch={}, startEpoch={}",
                now, latestSealableEpoch, startEpoch);

        for (long epochId = startEpoch; epochId <= latestSealableEpoch; epochId++) {
            try {
                List<ReadingSubmissionRequest> drained = epochIngressWindow.drainEpoch(epochId);
                EpochAggregate aggregate = epochAggregator.aggregate(epochId, drained);

                if (aggregate.totalReadings() == 0) {
                    commitCycleState.recordEmptyEpoch(epochId, now);
                    relayMetrics.recordCommitEmpty();
                    LOG.debug("epoch {} has no readings to commit", epochId);
                    continue;
                }

                CommitPublication publication = epochCommitPublisher.publish(aggregate);
                commitCycleState.recordCommitted(publication);
                relayMetrics.recordReadingsDrained(drained.size());
                relayMetrics.recordCommitSuccess();
            } catch (Exception ex) {
                commitCycleState.recordFailure("epoch " + epochId + " commit failed: " + ex.getMessage());
                relayMetrics.recordCommitFailure();
                LOG.error("epoch {} commit failed", epochId, ex);
                break;
            }
        }
    }
}
