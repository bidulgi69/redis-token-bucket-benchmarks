# Redis Token Bucket Benchmarks
This repository contains benchmark tests comparing different approaches to implement token bucket rate limiting using Redis.
Evaluate Bucket4j, Lua script execution, and several Redis client libraries (Jedis, Lettuce, Redisson) under controlled conditions using JMH.

## 🔍 Overview
This benchmark suite measures performance characteristic(latency percentiles) across different client libraries and implementations:
```text
- Bucket4j (distributed via Redis)
- Custom Lua script
- Clients tested:
  - Jedis 
  - Lettuce 
  - Redisson
```

## ⚙️ Benchmark Environment
- Apple M1, 10 CPUs
- JDK 17
- Redis 7.2.1 standalone, loopback(localhost)
- Bucket spec: 5,000 tokens (refills greedily 5,000 tokens per 1s)

## 📊 Metrics
### Latency (μs)
<div style="display: flex; justify-content: space-between;">
    <img width=590 src="https://i.ibb.co/b8wRJ3C/benchmark-comparison-client-2.png" alt="benchmark-comparison-client-2"/>
    <img width=590 src="https://i.ibb.co/JwbXNXMr/benchmark-comparison-client-4.png" alt="benchmark-comparison-client-4"/>
</div>
<div style="display: flex; justify-content: space-between;">
    <img width=590 src="https://i.ibb.co/WvRc9511/benchmark-comparison-client-1.png" alt="benchmark-comparison-client-1"/>
    <img width=590 src="https://i.ibb.co/fjqN7nt/benchmark-comparison-client-3.png" alt="benchmark-comparison-client-3"/>
</div>

<hr />

### Latency Comparison
<div style="display: flex; justify-content: space-between;">
    <img width=480 src="https://i.ibb.co/n8f7h9q9/benchmarks-2.png" alt="benchmarks-2">
    <img width=480 src="https://i.ibb.co/8nTdNzwq/benchmarks-1.png" alt="benchmarks-1">
</div>
<div style="display: flex; justify-content: space-between;">
    <img width=480 src="https://i.ibb.co/XZq65dP9/benchmarks-4.png" alt="benchmarks-4">
    <img width=480 src="https://i.ibb.co/rRpKQCSS/benchmarks-3.png" alt="benchmarks-3">
</div>
<div style="display: flex; justify-content: space-between;">
    <img width=480 src="https://i.ibb.co/gb73GfsH/benchmarks-6.png" alt="benchmarks-6">
    <img width=480 src="https://i.ibb.co/Q7rqLs74/benchmarks-5.png" alt="benchmarks-5">
</div>
