package protocol.vayu.relay.service.commit;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.api.dto.ReadingSubmissionRequest;
import protocol.vayu.relay.config.RelayProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpochCommitCoordinatorTest {

    @Test
    void runCommitCycleShouldCommitSealedEpochReadings() {
        RelayProperties properties = relayProperties();
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
        DefaultEpochAggregator aggregator = new DefaultEpochAggregator();
        CommitCycleState state = new CommitCycleState();

        EpochCommitPublisher publisher = aggregate -> new CommitPublication(
                aggregate.epochId(),
                "0xabc123",
                aggregate.totalReadings(),
                Instant.now().getEpochSecond()
        );

        EpochCommitCoordinator coordinator = new EpochCommitCoordinator(
                properties,
                store,
                aggregator,
                publisher,
                state
        );

        long now = Instant.now().getEpochSecond();
        long epochDuration = properties.epoch().durationSeconds();
        long committedEpoch = (now / epochDuration) - 1;
        long timestamp = Math.max(1, committedEpoch * epochDuration);

        store.enqueue(new ReadingSubmissionRequest(
                "0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff",
                committedEpoch,
                timestamp,
                100,
                200,
                null,
                null,
                null,
                null,
                null,
                "0x" + "1".repeat(130)
        ));

        coordinator.runCommitCycle();

        assertEquals(committedEpoch, state.lastCommittedEpoch());
        assertEquals("0xabc123", state.lastCommitTxHash());
        assertEquals(0, store.pendingReadings());
    }

    @Test
    void runCommitCycleShouldAdvanceWatermarkOnEmptyEpoch() {
        RelayProperties properties = relayProperties();
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
        DefaultEpochAggregator aggregator = new DefaultEpochAggregator();
        CommitCycleState state = new CommitCycleState();

        EpochCommitPublisher publisher = aggregate -> new CommitPublication(
                aggregate.epochId(),
                "0xunused",
                aggregate.totalReadings(),
                Instant.now().getEpochSecond()
        );

        EpochCommitCoordinator coordinator = new EpochCommitCoordinator(
                properties,
                store,
                aggregator,
                publisher,
                state
        );

        coordinator.runCommitCycle();

        assertTrue(state.lastCommittedEpoch() >= 0);
    }

    @Test
    void runCommitCycleShouldResumeFromLastCommittedEpochPlusOne() {
        RelayProperties properties = relayProperties();
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
        DefaultEpochAggregator aggregator = new DefaultEpochAggregator();
        CommitCycleState state = new CommitCycleState();

        List<Long> publishedEpochs = new ArrayList<>();
        EpochCommitPublisher publisher = aggregate -> {
            publishedEpochs.add(aggregate.epochId());
            return new CommitPublication(aggregate.epochId(), "0xresume",
                    aggregate.totalReadings(), Instant.now().getEpochSecond());
        };

        EpochCommitCoordinator coordinator = new EpochCommitCoordinator(
                properties, store, aggregator, publisher, state);

        long now = Instant.now().getEpochSecond();
        long epochDuration = properties.epoch().durationSeconds();
        long latestSealableEpoch = (now / epochDuration) - 1;

        // pre-commit the epoch just before latest; coordinator should resume at latest
        state.recordCommitted(new CommitPublication(latestSealableEpoch - 1, "0xprev", 1, now));

        // reading only in the latest sealable epoch
        store.enqueue(reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", latestSealableEpoch, epochDuration));

        coordinator.runCommitCycle();

        assertEquals(List.of(latestSealableEpoch), publishedEpochs,
                "coordinator must start at lastCommittedEpoch+1, not re-process already-committed epoch");
        assertEquals(latestSealableEpoch, state.lastCommittedEpoch());
        assertEquals(0, store.pendingReadings());
    }

    @Test
    void runCommitCycleShouldCommitMultipleSequentialEpochs() {
        RelayProperties properties = relayProperties();
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
        DefaultEpochAggregator aggregator = new DefaultEpochAggregator();
        CommitCycleState state = new CommitCycleState();

        List<Long> publishedEpochs = new ArrayList<>();
        EpochCommitPublisher publisher = aggregate -> {
            publishedEpochs.add(aggregate.epochId());
            return new CommitPublication(aggregate.epochId(), "0xmulti",
                    aggregate.totalReadings(), Instant.now().getEpochSecond());
        };

        EpochCommitCoordinator coordinator = new EpochCommitCoordinator(
                properties, store, aggregator, publisher, state);

        long now = Instant.now().getEpochSecond();
        long epochDuration = properties.epoch().durationSeconds();
        long latestSealableEpoch = (now / epochDuration) - 1;

        // 2 epochs behind so loop covers latestSealableEpoch-1 and latestSealableEpoch
        state.recordCommitted(new CommitPublication(latestSealableEpoch - 2, "0xold", 1, now));

        store.enqueue(reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", latestSealableEpoch - 1, epochDuration));
        store.enqueue(reading("0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff", latestSealableEpoch, epochDuration));

        coordinator.runCommitCycle();

        assertEquals(List.of(latestSealableEpoch - 1, latestSealableEpoch), publishedEpochs);
        assertEquals(latestSealableEpoch, state.lastCommittedEpoch());
        assertEquals(0, store.pendingReadings());
    }

    @Test
    void runCommitCycleShouldBreakAndRecordFailureOnPublisherError() {
        RelayProperties properties = relayProperties();
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
        DefaultEpochAggregator aggregator = new DefaultEpochAggregator();
        CommitCycleState state = new CommitCycleState();

        EpochCommitPublisher publisher = aggregate -> {
            throw new RuntimeException("rpc unavailable");
        };

        EpochCommitCoordinator coordinator = new EpochCommitCoordinator(
                properties, store, aggregator, publisher, state);

        long now = Instant.now().getEpochSecond();
        long epochDuration = properties.epoch().durationSeconds();
        long latestSealableEpoch = (now / epochDuration) - 1;

        state.recordCommitted(new CommitPublication(latestSealableEpoch - 2, "0xold", 1, now));

        store.enqueue(reading("0x1111111111111111111111111111111111111111",
                "0x0882830a1fffffff", latestSealableEpoch - 1, epochDuration));
        store.enqueue(reading("0x2222222222222222222222222222222222222222",
                "0x0882830a1fffffff", latestSealableEpoch, epochDuration));

        coordinator.runCommitCycle();

        assertTrue(state.lastFailureReason().contains("rpc unavailable"));
        // loop broke on first failing epoch — lastCommittedEpoch unchanged
        assertEquals(latestSealableEpoch - 2, state.lastCommittedEpoch());
        // first epoch was drained before publisher threw; second epoch reading remains
        assertEquals(1, store.pendingReadings());
    }

    @Test
    void runCommitCycleShouldAlwaysUpdateHeartbeat() {
        RelayProperties properties = relayProperties();
        InMemoryEpochReadingStore store = new InMemoryEpochReadingStore();
        DefaultEpochAggregator aggregator = new DefaultEpochAggregator();
        CommitCycleState state = new CommitCycleState();

        EpochCommitPublisher publisher = aggregate -> new CommitPublication(
                aggregate.epochId(), "0xhb", aggregate.totalReadings(),
                Instant.now().getEpochSecond());

        EpochCommitCoordinator coordinator = new EpochCommitCoordinator(
                properties, store, aggregator, publisher, state);

        assertEquals(0, state.lastWorkerHeartbeat());
        coordinator.runCommitCycle();
        assertTrue(state.lastWorkerHeartbeat() > 0);
    }

    private static ReadingSubmissionRequest reading(
            String reporter, String h3Index, long epochId, long epochDuration) {
        return new ReadingSubmissionRequest(
                reporter,
                h3Index,
                epochId,
                epochId * epochDuration + 1,
                100,
                200,
                null, null, null, null, null,
                "0x" + "1".repeat(130)
        );
    }

    private static RelayProperties relayProperties() {
        RelayProperties.Messages messages = new RelayProperties.Messages(
                "aqi must be greater than %d",
                "pm25 must be greater than %d",
                "timestamp is required",
                "timestamp is outside allowed tolerance window",
                "epochId does not match timestamp and epoch duration",
                "h3Index must be a 64-bit hex string",
                "h3Index must be valid hex",
                "h3Index resolution must be %d",
                "reporter can submit once every %d seconds"
        );

        RelayProperties.Validation validation = new RelayProperties.Validation(
                8,
                300,
                1,
                1,
                messages
        );

        RelayProperties.Epoch epoch = new RelayProperties.Epoch(3600, 60000, 300);
        RelayProperties.Eip712 eip712 = new RelayProperties.Eip712(
                "VayuProtocol",
                "1",
                84532,
                "0x0000000000000000000000000000000000000000"
        );
        RelayProperties.Security security = new RelayProperties.Security(true, false, eip712);
        return new RelayProperties(epoch, validation, security);
    }
}
