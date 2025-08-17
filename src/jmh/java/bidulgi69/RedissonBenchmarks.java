package bidulgi69;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.redisson.Bucket4jRedisson;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandAsyncService;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;
import org.redisson.connection.ConnectionManager;
import org.redisson.liveobject.core.RedissonObjectBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
@Fork(2)
@Threads(16)
public class RedissonBenchmarks extends RedisCommonEnv {

    private RedissonClient redisson;
    private RScript rs;
    private String luaSha;

    private ConnectionManager connectionManager;
    private RedissonBasedProxyManager<String> proxyManager;
    private BucketConfiguration bucketCfg;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        super.loadLua();

        Config cfg = new Config();
        cfg.useSingleServer()
            .setAddress(redisUrl)
            .setConnectionPoolSize(maxConnections)
            .setConnectionMinimumIdleSize(minIdleConnections)
            .setTcpNoDelay(true) // disable naggle
            .setKeepAlive(true);
        redisson = Redisson.create(cfg);

        rs = redisson.getScript(StringCodec.INSTANCE);
        luaSha = rs.scriptLoad(luaScript);

        connectionManager = ConfigSupport.createConnectionManager(cfg);
        CommandAsyncExecutor executor = new CommandAsyncService(connectionManager, null, RedissonObjectBuilder.ReferenceType.DEFAULT);
        proxyManager = Bucket4jRedisson.casBasedBuilder(executor).build();

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
        if (connectionManager != null) {
            connectionManager.shutdown(3_000, 5_000, TimeUnit.MILLISECONDS);
        }
        if (redisson != null) {
            redisson.shutdown(3_000, 5_000, TimeUnit.MILLISECONDS);
        }
    }

    @State(Scope.Thread)
    public static class ThreadState {
        String bucket4jKey;
        String luaKey;
        BucketProxy bucketProxy;
        Object[] argv;
        long threadId;

        @Setup(Level.Iteration)
        public void setupIteration(RedissonBenchmarks env) {
            threadId = Thread.currentThread().getId();

            bucket4jKey = env.keyFor("redisson:bucket4j:", threadId);
            luaKey = env.keyFor("redisson:lua:", threadId);

            bucketProxy = env.proxyManager.builder().build(bucket4jKey, () -> env.bucketCfg);

            argv = new Object[]{
                "" + env.capacity,
                "" + env.refillTokens,
                "" + env.refillMs,
                "1",
                "" + env.ttlSec
            };

            env.initIterationKeys(env::deleteKey, "bucket4j:", threadId);
            env.initIterationKeys(env::deleteKey, "lua:", threadId);
        }
    }

    private void deleteKey(String key) {
        RBucket<Object> b = redisson.getBucket(key, StringCodec.INSTANCE);
        b.unlink();
    }

    @Benchmark
    public void bucket4j_consume(ThreadState ts, Blackhole bh) {
        ConsumptionProbe probe = ts.bucketProxy.tryConsumeAndReturnRemaining(1L);
        bh.consume(probe.isConsumed());
    }

    @Benchmark
    public void redisson_lua_consume(ThreadState ts, Blackhole bh) {
        List<Object> ret = rs.evalSha(
            RScript.Mode.READ_WRITE,
            luaSha,
            RScript.ReturnType.MULTI,
            List.of(ts.luaKey),
            ts.argv
        );
        bh.consume(((Number) ret.get(0)).longValue());
    }
}
