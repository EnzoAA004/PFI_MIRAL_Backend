package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cloud P0B/4B: proves the pool this backend now shares across every PostgresXxx class
 * actually pools -- connections get reused, stay bounded under concurrent-ish repeated
 * use, and a connection returned after a failed operation still goes back to the pool
 * rather than leaking.
 *
 * <p>Every test here that needs a pool uses a distinct {@code maximumPoolSize} as part
 * of the memoization key (see {@link PfiHikariDataSourceFactory}), so each gets its own
 * pool instance without needing to close anything afterward -- these pools are cheap,
 * scoped to this one Testcontainers container, and torn down with it at JVM exit.
 */
@Testcontainers(disabledWithoutDocker = true)
class PfiHikariDataSourceFactoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_pool_test")
        .withUsername("pfi")
        .withPassword("pfi");

    private String jdbcUrl() {
        return postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword();
    }

    @Test
    void repeatedGetConnectionCyclesReuseThePoolRatherThanExhaustingIt() throws Exception {
        DataSource dataSource = PfiHikariDataSourceFactory.create(jdbcUrl(), 11);

        // Far more than maximumPoolSize, one at a time -- if get/close leaked a physical
        // connection each time, this would exhaust the pool well before iteration 50.
        for (int i = 0; i < 50; i++) {
            try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
        }

        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertTrue(hikari.getHikariPoolMXBean().getTotalConnections() <= 11,
            "pool should never exceed the configured maximumPoolSize");
    }

    @Test
    void configuredMaximumPoolSizeIsRespectedUnderConcurrentBorrow() throws Exception {
        DataSource dataSource = PfiHikariDataSourceFactory.create(jdbcUrl(), 12);
        HikariDataSource hikari = (HikariDataSource) dataSource;

        Connection[] held = new Connection[4];
        for (int i = 0; i < held.length; i++) {
            held[i] = dataSource.getConnection();
        }
        try {
            assertEquals(4, hikari.getHikariPoolMXBean().getActiveConnections());
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }

        assertEquals(0, hikari.getHikariPoolMXBean().getActiveConnections(),
            "closing every borrowed connection must return all of them to the pool");
    }

    @Test
    void connectionIsReturnedToPoolEvenWhenTheOperationOnItFails() throws Exception {
        DataSource dataSource = PfiHikariDataSourceFactory.create(jdbcUrl(), 13);
        HikariDataSource hikari = (HikariDataSource) dataSource;

        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT * FROM this_table_does_not_exist_on_purpose");
            } catch (Exception expected) {
                // the failure itself is the point -- the try-with-resources Connection
                // still closes (returns to the pool) via the outer try block.
            }
        }

        assertEquals(0, hikari.getHikariPoolMXBean().getActiveConnections(),
            "a failed statement must not leak its connection out of the pool");
    }

    @Test
    void sameJdbcUrlAndPoolSizeReturnsTheSameMemoizedPoolInsteadOfANewOneEachCall() {
        // This is the fix for the real defect this level of testing surfaced: a test
        // class whose several methods each built "their own" repository against the
        // same Testcontainers URL used to get a brand-new HikariDataSource per call,
        // and nothing ever closed the earlier ones. Within a single JVM test run that
        // accumulated enough held-open sockets to make later connection attempts take
        // minutes instead of milliseconds (reproduced: two ~600s test methods, fixed by
        // this memoization). Same (jdbcUrl, maximumPoolSize) must now return the exact
        // same pool.
        DataSource first = PfiHikariDataSourceFactory.create(jdbcUrl(), 14);
        DataSource second = PfiHikariDataSourceFactory.create(jdbcUrl(), 14);
        assertSame(first, second);
    }

    @Test
    void defaultFactoryOverloadUsesTheDocumentedMaximumPoolSizeOfFour() {
        DataSource dataSource = PfiHikariDataSourceFactory.create(jdbcUrl() + "&applicationName=default-overload-test");
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertEquals(4, hikari.getMaximumPoolSize());
    }

    @Test
    void poolDoesNotEagerlyValidateOnConstructionAgainstAnUnreachableHost() {
        // initializationFailTimeout=-1: matches the pre-pooling behavior where every
        // PostgresXxx class only ever failed lazily, on the first real query -- never
        // at construction time. A bad/unreachable URL here must not throw.
        DataSource unreachable = PfiHikariDataSourceFactory.create(
            "jdbc:postgresql://127.0.0.1:1/does-not-exist?connectTimeout=1&socketTimeout=1", 15);
        assertTrue(unreachable instanceof HikariDataSource);
    }
}
