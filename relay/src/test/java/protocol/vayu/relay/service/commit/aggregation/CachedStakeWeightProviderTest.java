package protocol.vayu.relay.service.commit.aggregation;

import org.junit.jupiter.api.Test;
import protocol.vayu.relay.config.RelayProperties;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachedStakeWeightProviderTest {

    private static final String REPORTER = "0x1111111111111111111111111111111111111111";
    private static final BigInteger STAKE_100 = BigInteger.valueOf(100);

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Config with a generous TTL so entries don't expire during tests. */
    private static RelayProperties.StakeCache config(boolean failOpen) {
        return new RelayProperties.StakeCache(3600, 1000, failOpen, 50, 30, 10);
    }

    /** Config with a minimal sliding window so we can trip the circuit in tests. */
    private static RelayProperties.StakeCache smallWindowConfig(boolean failOpen) {
        return new RelayProperties.StakeCache(3600, 1000, failOpen, 100, 60, 2);
    }

    private static StakeWeightProvider fixedDelegate(BigInteger value) {
        return reporter -> value;
    }

    private static StakeWeightProvider failingDelegate() {
        return reporter -> { throw new StakeQueryException("rpc down"); };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cold / hot path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void coldPathShouldFetchFromDelegateAndReturnStake() {
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(fixedDelegate(STAKE_100), config(true));

        BigInteger result = provider.stakeOf(REPORTER);

        assertThat(result).isEqualByComparingTo(STAKE_100);
    }

    @Test
    void hotCacheHitShouldNotInvokeDelegate() {
        AtomicInteger callCount = new AtomicInteger();
        StakeWeightProvider counting = reporter -> {
            callCount.incrementAndGet();
            return STAKE_100;
        };
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(counting, config(true));

        provider.stakeOf(REPORTER);  // cold — populates cache
        provider.stakeOf(REPORTER);  // hot — should not call delegate
        provider.stakeOf(REPORTER);  // hot

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void cacheKeysShouldBeLowercased() {
        // Delegate should be called only once for case-variant addresses
        AtomicInteger callCount = new AtomicInteger();
        StakeWeightProvider counting = reporter -> {
            callCount.incrementAndGet();
            return STAKE_100;
        };
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(counting, config(true));

        provider.stakeOf("0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        provider.stakeOf("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertThat(callCount.get()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fail-open: stale cache fallback
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void failOpenShouldReturnStaleCacheValueWhenDelegateFails() {
        AtomicInteger callCount = new AtomicInteger();
        // First call succeeds, subsequent calls throw
        StakeWeightProvider flaky = reporter -> {
            if (callCount.incrementAndGet() == 1) return STAKE_100;
            throw new StakeQueryException("rpc down");
        };
        // Use a TTL of 0 seconds so the hot cache expires immediately (still populates stale)
        RelayProperties.StakeCache shortTtlConfig = new RelayProperties.StakeCache(0, 1000, true, 50, 30, 10);
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(flaky, shortTtlConfig);

        provider.stakeOf(REPORTER);   // cold — caches in both hot and stale

        // Hot cache is now expired; delegate fails; stale cache should be returned
        BigInteger stale = provider.stakeOf(REPORTER);

        assertThat(stale).isEqualByComparingTo(STAKE_100);
    }

    @Test
    void failOpenShouldReturnBigIntegerOneWhenNoCacheAndDelegateFails() {
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(failingDelegate(), config(true));

        BigInteger result = provider.stakeOf(REPORTER);

        assertThat(result).isEqualByComparingTo(BigInteger.ONE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fail-closed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void failClosedShouldThrowWhenNoCacheAndDelegateFails() {
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(failingDelegate(), config(false));

        assertThatThrownBy(() -> provider.stakeOf(REPORTER))
                .isInstanceOf(StakeQueryException.class);
    }

    @Test
    void failClosedShouldReturnStaleCacheValueEvenWhenDelegateFails() {
        AtomicInteger callCount = new AtomicInteger();
        StakeWeightProvider flaky = reporter -> {
            if (callCount.incrementAndGet() == 1) return STAKE_100;
            throw new StakeQueryException("rpc down");
        };
        // TTL=0: hot cache expires immediately; stale cache should still be used
        RelayProperties.StakeCache shortTtlConfig = new RelayProperties.StakeCache(0, 1000, false, 50, 30, 10);
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(flaky, shortTtlConfig);

        provider.stakeOf(REPORTER);  // cold — populates stale cache

        // Fail-closed, but stale entry exists → should return stale (not throw)
        BigInteger result = provider.stakeOf(REPORTER);

        assertThat(result).isEqualByComparingTo(STAKE_100);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Circuit breaker: open-circuit path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void circuitBreakerShouldOpenAfterSlidingWindowExceedsThreshold() {
        // Window of 2, threshold 100% → 2 failures opens the circuit
        AtomicInteger delegateCallCount = new AtomicInteger();
        StakeWeightProvider alwaysFailing = reporter -> {
            delegateCallCount.incrementAndGet();
            throw new StakeQueryException("rpc down");
        };
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(alwaysFailing, smallWindowConfig(true));

        // Two calls fill the window and open the circuit (fail-open → returns BigInteger.ONE each time)
        provider.stakeOf("0x1111111111111111111111111111111111111111");
        provider.stakeOf("0x2222222222222222222222222222222222222222");

        int callsBeforeOpen = delegateCallCount.get();

        // With the circuit open, subsequent calls should NOT reach the delegate
        provider.stakeOf("0x3333333333333333333333333333333333333333");
        provider.stakeOf("0x4444444444444444444444444444444444444444");

        assertThat(delegateCallCount.get()).isEqualTo(callsBeforeOpen);
    }

    @Test
    void openCircuitShouldReturnFallbackWeight_failOpen() {
        CachedStakeWeightProvider provider = new CachedStakeWeightProvider(failingDelegate(), smallWindowConfig(true));

        // Fill the sliding window to open the circuit
        provider.stakeOf("0x1111111111111111111111111111111111111111");
        provider.stakeOf("0x2222222222222222222222222222222222222222");

        // Circuit is open; reporter with no cache entry should get fallback weight 1
        BigInteger result = provider.stakeOf("0x5555555555555555555555555555555555555555");

        assertThat(result).isEqualByComparingTo(BigInteger.ONE);
    }
}
