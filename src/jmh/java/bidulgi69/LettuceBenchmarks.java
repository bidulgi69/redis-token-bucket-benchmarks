package bidulgi69;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.NettyCustomizer;
import io.lettuce.core.support.ConnectionPoolSupport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

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
public class LettuceBenchmarks extends RedisCommonEnv {

    RedisClient client;
    GenericObjectPool<StatefulRedisConnection<String, String>> pool;
    String luaSha;

    LettuceBasedProxyManager<byte[]> proxyManager;
    BucketConfiguration bucketCfg;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        super.loadLua();

        ClientResources clientResources = DefaultClientResources.builder()
            .nettyCustomizer(new NettyCustomizer() {
                @Override
                public void afterBootstrapInitialized(Bootstrap bootstrap) {
                    bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
                    bootstrap.option(ChannelOption.TCP_NODELAY, true);
                    bootstrap.option(ChannelOption.SO_REUSEADDR, true);
                }
            })
            .build();
        client = RedisClient.create(clientResources, redisUrl);
        ClientOptions opts = ClientOptions.builder()
            .socketOptions(SocketOptions.builder()
                .tcpNoDelay(true)
                .keepAlive(true)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            )
            .timeoutOptions(TimeoutOptions.builder()
                .fixedTimeout(Duration.ofSeconds(5))
                .build()
            )
            .build();
        client.setOptions(opts);

        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> commands = conn.sync();
            luaSha = commands.scriptLoad(luaScript);
        }

        GenericObjectPoolConfig<StatefulRedisConnection<String, String>> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(maxConnections);
        cfg.setMinIdle(minIdleConnections);
        cfg.setTestOnBorrow(true);
        cfg.setTestOnReturn(true);
        pool = ConnectionPoolSupport.createGenericObjectPool(client::connect, cfg);

        proxyManager = Bucket4jLettuce.casBasedBuilder(client).build();
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
            client.shutdown();
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
        public void setup(LettuceBenchmarks env) {
            threadId = Thread.currentThread().getId();

            bucket4jKey = env.keyFor("lettuce:bucket4j:", threadId);
            luaKey = env.keyFor("lettuce:lua:", threadId);

            bucketProxy = env.proxyManager.builder().build(bucket4jKey.getBytes(StandardCharsets.UTF_8), () -> env.bucketCfg);

            argv = new String[]{
                "" + env.capacity,
                "" + env.refillTokens,
                "" + env.refillMs,
                "1",
                "" + env.ttlSec
            };

            env.initIterationKeys(env::deleteKey, "lettuce:bucket4j:", threadId);
            env.initIterationKeys(env::deleteKey, "lettuce:lua:", threadId);
        }
    }

    private void deleteKey(String key) {
        client.connect().sync().unlink(key);
    }

    @Benchmark
    public void bucket4j_consume(ThreadState ts, Blackhole bh) {
        ConsumptionProbe probe = ts.bucketProxy.tryConsumeAndReturnRemaining(1L);
        bh.consume(probe.isConsumed());
    }

    @Benchmark
    public void lua_consume(ThreadState ts, Blackhole bh) {
        List<Object> raw;
        try (StatefulRedisConnection<String, String> connection = pool.borrowObject()) {
            RedisCommands<String, String> commands = connection.sync();
            raw = commands.evalsha(luaSha, ScriptOutputType.MULTI, new String[]{ ts.luaKey }, ts.argv);
        } catch (Exception e) {
            // mark as failed
            raw = List.of(0L);
        }
        bh.consume(((Number)raw.get(0)).longValue() == 1);
    }
}
