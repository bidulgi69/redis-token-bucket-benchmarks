package bidulgi69;

import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Param;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@State(Scope.Benchmark)
public abstract class RedisCommonEnv {

    public final String host = "localhost";
    public final int port = 6379;
    public final String redisUrl = String.format("redis://%s:%d", host, port);
    public final String keyPrefix = "bench:";
    public final String resource = "script.lua";

    // token bucket 파라미터
    @Param({"5000"}) public int capacity;
    @Param({"5000"}) public int refillTokens;
    @Param({"1000"}) public int refillMs;
    public final int ttlSec = 30;

    // 경합/비경합 시나리오를 검증
    @Param({"contended", "non-contended"})
    public String scenario;

    // sha
    public String luaScript;

    // connection pool
    public final int maxConnections = 32;
    public final int minIdleConnections = 4;

    public void loadLua() throws IOException {
        try (InputStream in = Objects.requireNonNull(
            RedisCommonEnv.class.getClassLoader().getResourceAsStream(resource),
            "Lua resource not found: " + resource)) {
            this.luaScript = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public boolean isContendedScenario() {
        return "contended".equalsIgnoreCase(scenario);
    }

    public String keyFor(String suffix, long threadId) {
        if (isContendedScenario()) {
            // 모든 스레드가 같은 키를 사용
            return keyPrefix + suffix;
        }
        return keyPrefix + suffix + threadId;
    }

    // cleanup
    public interface KeyDeleter { void del(String key); }

    public void initIterationKeys(KeyDeleter deleter, String suffix, Long threadIdOpt) {
        if (isContendedScenario()) {
            deleter.del(keyFor(suffix, 0));
        } else if (threadIdOpt != null) {
            deleter.del(keyFor(suffix, threadIdOpt));
        }
    }
}

