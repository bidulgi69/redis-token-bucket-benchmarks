package bidulgi69;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.jedis.Bucket4jJedis;
import io.github.bucket4j.redis.jedis.cas.JedisBasedProxyManager;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
@Fork(2)
@Threads(16)
public class JedisBenchmarks extends RedisCommonEnv {

    JedisPool pool;
    String luaSha;

    JedisBasedProxyManager<byte[]> proxyManager;
    BucketConfiguration bucketCfg;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        super.loadLua();

        GenericObjectPoolConfig<Jedis> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(maxConnections);
        cfg.setMinIdle(minIdleConnections);
        cfg.setTestOnBorrow(true);
        cfg.setTestOnReturn(true);
        pool = new JedisPool(cfg, "localhost", 6379, 10_000, false);
        try (Jedis j = pool.getResource()) {
            luaSha = j.scriptLoad(luaScript);
        }

        proxyManager = Bucket4jJedis.casBasedBuilder(pool).build();
        bucketCfg = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, Duration.ofMillis(refillMs))
                .initialTokens(capacity)
                .build()
            ).build();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (pool != null) {
            pool.close();
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {
        String bucket4jKey;
        String luaKey;
        BucketProxy bucketProxy;
        String[] argv;
        long threadId;

        @Setup(Level.Iteration)
        public void setup(JedisBenchmarks env) {
            threadId = Thread.currentThread().getId();

            bucket4jKey = env.keyFor("jedis:bucket4j:", threadId);
            luaKey = env.keyFor("jedis:lua:", threadId);

            bucketProxy = env.proxyManager.builder().build(bucket4jKey.getBytes(StandardCharsets.UTF_8), () -> env.bucketCfg);

            argv = new String[]{
                luaKey,
                "" + env.capacity,
                "" + env.refillTokens,
                "" + env.refillMs,
                "1",
                "" + env.ttlSec
            };

            env.initIterationKeys(env::deleteKey, "jedis:bucket4j:", threadId);
            env.initIterationKeys(env::deleteKey, "jedis:lua:", threadId);
        }
    }

    private void deleteKey(String key) {
        try (Jedis j = pool.getResource()) {
            j.unlink(key);
        }
    }

    @Benchmark
    public void bucket4j_consume(ThreadState ts, Blackhole bh) {
        ConsumptionProbe probe = ts.bucketProxy.tryConsumeAndReturnRemaining(1L);
        bh.consume(probe.isConsumed());
    }

    @SuppressWarnings("unchecked")
    @Benchmark
    public void lua_consume(ThreadState ts, Blackhole bh) {
        List<Object> raw;
        try (Jedis j = pool.getResource()) {
            raw = (List<Object>) j.evalsha(luaSha, 1, ts.argv);
        }
        bh.consume(((Number)raw.get(0)).longValue() == 1);
    }
}
