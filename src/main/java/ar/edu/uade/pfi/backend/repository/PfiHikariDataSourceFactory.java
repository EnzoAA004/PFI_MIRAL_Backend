package ar.edu.uade.pfi.backend.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * El único lugar donde se construye un {@link DataSource} con pool en este backend -- lo usa
 * {@code PfiDataSourceConfig} para el pool de toda la aplicación, y cada constructor
 * {@code (databaseUrl, applyMigrations)} exclusivo para tests de las clases {@code PostgresXxx}
 * para que los tests obtengan un pool chico propio contra su instancia de Testcontainers
 * en lugar de un segundo mecanismo de pooling distinto.
 *
 * <p>Memoizado por {@code (jdbcUrl, maximumPoolSize)}: una clase de test cuyos varios
 * métodos construyen cada uno "su propio" repositorio/servicio contra la misma URL de Testcontainers
 * recibe de vuelta el *mismo* pool, no uno nuevo por llamada. Sin esto, una clase de test
 * con N métodos abría N pools de corta vida que nadie cerraba nunca -- sin problema
 * en aislamiento, pero los sockets abiertos acumulados contra el port forwarding de Docker Desktop
 * degradaban medible la latencia de nuevas conexiones a medida que se apilaban dentro de una misma
 * corrida de tests de JVM (minutos, no milisegundos, confirmado empíricamente antes de este fix).
 * Producción no se ve afectada: {@code PfiDataSourceConfig} llama a esto exactamente una vez.
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
        // 0: una instancia de Cloud Run puede quedar inactiva por largos períodos (min-instances=0);
        // una conexión inactiva pero abierta solo envejece hacia maxLifetime sin ningún beneficio.
        config.setMinimumIdle(0);
        // Fallar rápido en lugar de encolar requests indefinidamente detrás de un pool desbordado --
        // un pool acotado bajo sobrecarga real debe aflorar como un timeout claro, no como un
        // request que se cuelga hasta que se dispara el timeout propio del contenedor.
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        // Cloud SQL rota por su cuenta los certificados efímeros del Java Connector;
        // reciclar las conexiones del pool bastante antes de una hora evita que este pool
        // mantenga alguna más allá de esa rotación.
        config.setMaxLifetime(1_700_000);
        // -1: no validar la conectividad mientras se construye el pool mismo.
        // Todas las clases PostgresXxx históricamente fallaron de forma diferida, en la primera llamada real a
        // DriverManager.getConnection() dentro de un método -- la validación anticipada acá
        // convertiría eso en una falla en el constructor, un cambio de comportamiento del que
        // varios tests dependen que no ocurra (p. ej. un bean construido una vez al arranque de
        // Spring, pero con Cloud SQL inalcanzable solo más tarde).
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
