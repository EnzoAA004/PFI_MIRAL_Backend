package ar.edu.uade.pfi.backend.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * The one place a pooled {@link DataSource} gets built in this backend -- used by
 * {@code PfiDataSourceConfig} for the application-wide pool, and by each
 * {@code PostgresXxx} class's test-only {@code (databaseUrl, applyMigrations)}
 * constructor so tests get a small pool of their own against their Testcontainers
 * instance instead of a second, different pooling mechanism.
 *
 * <p>Memoized per {@code (jdbcUrl, maximumPoolSize)}: a test class whose several
 * methods each build "their own" repository/service against the same Testcontainers
 * URL get back the *same* pool, not a fresh one per call. Without this, a test class
 * with N methods opened N short-lived pools that nothing ever closed -- fine in
 * isolation, but the accumulated held-open sockets against Docker Desktop's port
 * forwarding measurably degraded new-connection latency as more piled up within one
 * JVM test run (minutes, not milliseconds, confirmed empirically before this fix).
 * Production is unaffected: {@code PfiDataSourceConfig} calls this exactly once.
 */
public final class PfiHikariDataSourceFactory {

    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 4;
    private static final Map<String, DataSource> POOLS_BY_KEY = new ConcurrentHashMap<>();

    private PfiHikariDataSourceFactory() {
    }

    public static DataSource create(String jdbcUrl) {
        return create(jdbcUrl, DEFAULT_MAXIMUM_POOL_SIZE);
    }

    public static DataSource create(String jdbcUrl, int maximumPoolSize) {
        return POOLS_BY_KEY.computeIfAbsent(jdbcUrl + "|" + maximumPoolSize,
            key -> build(jdbcUrl, maximumPoolSize));
    }

    private static DataSource build(String jdbcUrl, int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setPoolName("pfi-postgres-pool");
        config.setMaximumPoolSize(maximumPoolSize);
        // 0: a Cloud Run instance can sit idle for long stretches (min-instances=0);
        // an idle-but-open connection just ages toward maxLifetime for no benefit.
        config.setMinimumIdle(0);
        // Fail fast rather than queuing requests indefinitely behind a starved pool --
        // a bounded pool under real overload should surface as a clear timeout, not a
        // request that hangs until the container's own request timeout fires.
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        // Cloud SQL rotates the Java Connector's ephemeral certs on its own schedule;
        // recycling pooled connections well under an hour keeps this pool from ever
        // holding one past that rotation.
        config.setMaxLifetime(1_700_000);
        // -1: don't validate connectivity while the pool itself is being constructed.
        // Every PostgresXxx class historically failed lazily, on the first real
        // DriverManager.getConnection() call inside a method -- eager validation here
        // would turn that into a constructor-time failure instead, a behavior change
        // several tests rely on not happening (e.g. a bean built once at Spring
        // startup, but Cloud SQL only unreachable later).
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
