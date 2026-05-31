package protocol.vayu.relay.service.commit.aggregation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.vayu.relay.config.RelayProperties;

import java.math.BigInteger;
import java.time.Duration;

/**
 * {@link StakeWeightProvider} decorator that adds:
 * <ul>
 *   <li>Caffeine TTL cache keyed on lowercase reporter address (hot path: no RPC call).</li>
 *   <li>Resilience4j circuit breaker around the delegate's {@code eth_call}.</li>
 *   <li>Configurable fail-open / fail-closed policy on RPC failure or open circuit.</li>
 * </ul>
 *
 * <h3>Fail-open policy (default):</h3>
 * When the circuit is open or the delegate throws {@link StakeQueryException}:
 * <ol>
 *   <li>If a previous (possibly stale) value exists in the stale-cache, return it with a WARN log.</li>
 *   <li>Otherwise return {@code BigInteger.ONE} with a WARN log.</li>
 * </ol>
 * The on-chain settlement contract is the authoritative settlement layer; a brief window of
 * accepting a reporter whose current stake is unknown is preferable to rejecting all submissions
 * during an RPC outage.
 *
 * <h3>Fail-closed policy ({@code fail-open=false}):</h3>
 * If neither the hot cache nor the stale cache can satisfy the request, a
 * {@link StakeQueryException} is (re-)thrown, which the caller must handle.
 */
public class CachedStakeWeightProvider implements StakeWeightProvider {

    private static final Logger LOG = LoggerFactory.getLogger(CachedStakeWeightProvider.class);

    private final StakeWeightProvider delegate;
    /** Short-lived fresh cache (TTL eviction). */
    private final Cache<String, BigInteger> hotCache;
    /**
     * Last-known-good values — no TTL eviction (survives hot-cache expiry) but bounded to
     * {@code maxSize} entries (LRU). Used as a fallback when the delegate is unavailable.
     */
    private final Cache<String, BigInteger> staleCache;
    private final CircuitBreaker circuitBreaker;
    private final boolean failOpen;

    public CachedStakeWeightProvider(StakeWeightProvider delegate, RelayProperties.StakeCache config) {
        this.delegate = delegate;
        this.failOpen = config.failOpen();

        this.hotCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(config.ttlSeconds()))
                .maximumSize(config.maxSize())
                .build();

        this.staleCache = Caffeine.newBuilder()
                .maximumSize(config.maxSize())
                .build();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(config.cbFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(config.cbWaitDurationSeconds()))
                .slidingWindowSize(config.cbSlidingWindowSize())
                .recordExceptions(StakeQueryException.class)
                .build();
        this.circuitBreaker = CircuitBreaker.of("stake-rpc", cbConfig);
    }

    @Override
    public BigInteger stakeOf(String reporter) {
        String key = reporter.toLowerCase();

        // Hot path: fresh cache hit — no circuit breaker overhead, no RPC
        BigInteger cached = hotCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // Cold path: delegate call protected by circuit breaker
        try {
            BigInteger stake = circuitBreaker.executeCallable(() -> delegate.stakeOf(key));
            hotCache.put(key, stake);
            staleCache.put(key, stake);
            return stake;
        } catch (CallNotPermittedException e) {
            // Circuit is open — delegate is not called at all
            return handleFailure(key, e);
        } catch (StakeQueryException e) {
            // Delegate threw — circuit breaker has already recorded this as a failure
            return handleFailure(key, e);
        } catch (Exception e) {
            // Unexpected exception from the callable machinery
            return handleFailure(key, new StakeQueryException("unexpected stake query error for " + key, e));
        }
    }

    private BigInteger handleFailure(String key, Exception cause) {
        BigInteger stale = staleCache.getIfPresent(key);
        if (stale != null) {
            LOG.warn("stake RPC unavailable for {}, returning stale cached value {} wei. Cause: {}",
                    key, stale, cause.getMessage());
            return stale;
        }
        if (failOpen) {
            LOG.warn("stake RPC unavailable for {} with no cached value, using fallback weight 1. Cause: {}",
                    key, cause.getMessage());
            return BigInteger.ONE;
        }
        LOG.error("stake RPC unavailable for {} (fail-closed, no cache entry). Cause: {}", key, cause.getMessage());
        if (cause instanceof StakeQueryException sqe) {
            throw sqe;
        }
        throw new StakeQueryException("stake check failed for " + key, cause);
    }

    /** Forces synchronous Caffeine maintenance on the stale cache. For use in tests only. */
    void cleanUpStaleCacheForTest() {
        staleCache.cleanUp();
    }
}
