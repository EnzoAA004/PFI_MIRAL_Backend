package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El esquema no se construye en silencio.
 *
 * <p>La ruta de las migraciones es relativa al working directory. Cuando no resolvia, el runner
 * devolvia una lista vacia: el servicio levantaba contra un esquema inexistente y fallaba recien en
 * la primera consulta, con un error que no menciona migraciones. Un arranque que no puede construir
 * el esquema tiene que morir donde esta el problema.
 *
 * <p>No necesitan base: la validacion del directorio ocurre antes de tocar la conexion, que es
 * justamente lo que se quiere —fallar antes de abrir nada—.
 */
class SqlMigrationRunnerTest {

  @Test
  void unDirectorioInexistenteFallaYDiceCualBuscoYDesdeDonde(@TempDir Path tmp) {
    Path missing = tmp.resolve("no-existe");

    SqlMigrationRunner.MigrationsUnavailableException ex =
        assertThrows(
            SqlMigrationRunner.MigrationsUnavailableException.class,
            () -> new SqlMigrationRunner(missing).apply(null));

    // El mensaje tiene que alcanzar para diagnosticar sin adjuntar un debugger: la
    // ruta que se busco y el directorio desde el que se la resolvio.
    assertTrue(ex.getMessage().contains(missing.toAbsolutePath().toString()));
    assertTrue(ex.getMessage().contains("working directory"));
  }

  @Test
  void unDirectorioSinSqlTambienFalla(@TempDir Path tmp) throws Exception {
    Path empty = Files.createDirectory(tmp.resolve("migrations"));
    Files.writeString(empty.resolve("LEEME.md"), "no soy una migracion");

    SqlMigrationRunner.MigrationsUnavailableException ex =
        assertThrows(
            SqlMigrationRunner.MigrationsUnavailableException.class,
            () -> new SqlMigrationRunner(empty).apply(null));

    assertTrue(ex.getMessage().contains("ningun .sql"));
  }

  /**
   * El default es el que usan los dos componentes que migran. Si alguien lo cambia sin tocar el
   * Dockerfile —que copia docs/migrations al WORKDIR— el arranque se rompe en el contenedor y no en
   * el build.
   */
  @Test
  void elDirectorioPorDefectoEsElQueCopiaElDockerfile() {
    assertTrue(
        SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY
            .toString()
            .replace('\\', '/')
            .endsWith("docs/migrations"));
  }

  /** Las migraciones del repo estan donde el default las busca, y hay al menos una. */
  @Test
  void elRepoTieneSusMigracionesDondeElDefaultLasBusca() throws Exception {
    Path directory = SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY;
    assertTrue(Files.isDirectory(directory), "no existe " + directory.toAbsolutePath());
    try (var stream = Files.list(directory)) {
      assertTrue(stream.anyMatch(path -> path.getFileName().toString().endsWith(".sql")));
    }
  }

  @Test
  void liberaElAdvisoryLockDeSesionAntesDeDevolverLaConexionHikari(@TempDir Path tmp)
      throws Exception {
    Path migrations = Files.createDirectory(tmp.resolve("migrations"));
    Files.writeString(migrations.resolve("V1_noop.sql"), "SELECT 1");

    List<String> preparedSql = new ArrayList<>();
    List<Long> advisoryLockKeys = new ArrayList<>();
    Connection connection =
        proxy(
            Connection.class,
            (object, method, args) -> {
              if (method.getName().equals("prepareStatement")) {
                String sql = (String) args[0];
                preparedSql.add(sql);
                return preparedStatementProxy(sql, advisoryLockKeys);
              }
              if (method.getName().equals("createStatement")) return statementProxy();
              return defaultValue(method.getReturnType());
            });

    new SqlMigrationRunner(migrations).apply(connection);

    assertTrue(preparedSql.contains("SELECT pg_advisory_unlock(?)"));
    assertTrue(advisoryLockKeys.contains(8_921_364_501_774_233L));
  }

  private static PreparedStatement preparedStatementProxy(String sql, List<Long> lockKeys) {
    return proxy(
        PreparedStatement.class,
        (object, method, args) -> {
          if (method.getName().equals("setLong") && sql.contains("pg_advisory_")) {
            lockKeys.add((Long) args[1]);
          }
          if (method.getName().equals("executeQuery")) {
            return proxy(
                ResultSet.class,
                (result, resultMethod, resultArgs) -> {
                  if (resultMethod.getName().equals("next")) return true;
                  if (resultMethod.getName().equals("getBoolean")) return true;
                  return defaultValue(resultMethod.getReturnType());
                });
          }
          if (method.getName().equals("execute")) return true;
          return defaultValue(method.getReturnType());
        });
  }

  private static Statement statementProxy() {
    return proxy(
        Statement.class,
        (object, method, args) -> {
          if (method.getName().equals("execute")) return true;
          return defaultValue(method.getReturnType());
        });
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    if (type == double.class) return 0d;
    if (type == char.class) return '\0';
    return null;
  }
}
