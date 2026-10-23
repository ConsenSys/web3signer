/*
 * Copyright 2022 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package db;

import tech.pegasys.web3signer.slashingprotection.DbConnection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.ConnectionInfo;
import io.zonky.test.db.postgres.embedded.DatabasePreparer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.postgres.embedded.FlywayPreparer;
import io.zonky.test.db.postgres.embedded.PreparedDbProvider;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;

public class DatabaseUtil {
  public static final String USERNAME = "postgres";
  public static final String PASSWORD = "postgres";
  public static final String MIGRATIONS_LOCATION = "/migrations/postgresql/";

  /**
   * Pooling is off: the Postgres cluster is shared by every test in the JVM, so per-test pools
   * would keep their {@code minimumIdle} connections open for the rest of the run and exhaust the
   * server's {@code max_connections}. Unpooled connections are closed when the Jdbi handle is
   * closed.
   */
  public static final boolean DB_CONNECTION_POOL_ENABLED = false;

  /**
   * Zonky waits 10 seconds for a freshly started postmaster to accept connections, which a cold
   * start (initdb plus postmaster start under load) can exceed. The wait applies to the single
   * cluster a test fork starts.
   */
  private static final Duration PG_STARTUP_WAIT = Duration.ofMinutes(2);

  private static final List<Consumer<EmbeddedPostgres.Builder>> BUILDER_CUSTOMIZERS =
      List.of(builder -> builder.setPGStartupWait(PG_STARTUP_WAIT));

  /**
   * One Postgres cluster per JVM, shared by every test of the fork. Each call to {@link #create()}
   * or {@link #createWithoutMigration()} issues a {@code CREATE DATABASE} cloning a prepared
   * template, so tests stay isolated while {@code initdb} runs once per fork instead of once per
   * test.
   */
  private static final DatabasePreparer MIGRATED_TEMPLATE_PREPARER =
      FlywayPreparer.forClasspathLocation(MIGRATIONS_LOCATION);

  private static final int CLUSTER_START_ATTEMPTS = 3;

  private static volatile PreparedDbProvider databaseProvider;

  public static TestDatabaseInfo create() {
    final PreparedDbProvider provider = databaseProvider();
    return create(provider, true);
  }

  /**
   * A clone of the migrated template with its schema removed, for the tests that need an unmigrated
   * database.
   */
  public static TestDatabaseInfo createWithoutMigration() {
    final TestDatabaseInfo testDatabaseInfo = create(databaseProvider(), false);
    testDatabaseInfo
        .getJdbi()
        .useHandle(
            h -> {
              h.execute("DROP SCHEMA public CASCADE");
              h.execute("CREATE SCHEMA public");
            });
    return testDatabaseInfo;
  }

  /**
   * The cluster takes {@code initdb} plus a postmaster start, which can exceed zonky's wait when
   * the machine is busy, so a failed start is retried instead of leaving the class unusable. The
   * provider is created on first use rather than in a static initializer for the same reason: a
   * failure must not poison every later test of the JVM.
   */
  private static PreparedDbProvider databaseProvider() {
    PreparedDbProvider provider = databaseProvider;
    if (provider == null) {
      provider = startCluster();
      databaseProvider = provider;
    }
    return provider;
  }

  private static PreparedDbProvider startCluster() {
    RuntimeException failure = null;
    for (int attempt = 1; attempt <= CLUSTER_START_ATTEMPTS; attempt++) {
      try {
        return PreparedDbProvider.forPreparer(MIGRATED_TEMPLATE_PREPARER, BUILDER_CUSTOMIZERS);
      } catch (final RuntimeException e) {
        failure = e;
      }
    }
    throw failure;
  }

  private static TestDatabaseInfo create(
      final PreparedDbProvider databaseProvider, final boolean migrated) {
    try {
      final ConnectionInfo connectionInfo = databaseProvider.createNewDatabase();
      final DataSource dataSource =
          databaseProvider.createDataSourceFromConnectionInfo(connectionInfo);
      final String databaseUrl = databaseUrl(connectionInfo);
      final Jdbi jdbi =
          DbConnection.createConnection(
              databaseUrl, USERNAME, PASSWORD, null, DB_CONNECTION_POOL_ENABLED);
      final Optional<Flyway> flyway =
          migrated
              ? Optional.of(
                  Flyway.configure().locations(MIGRATIONS_LOCATION).dataSource(dataSource).load())
              : Optional.empty();
      return new TestDatabaseInfo(connectionInfo, dataSource, jdbi, flyway);
    } catch (final SQLException e) {
      throw new UncheckedIOException(
          "Unable to create embedded postgres database", new IOException(e));
    }
  }

  private static String databaseUrl(final ConnectionInfo connectionInfo) {
    return String.format(
        "jdbc:postgresql://localhost:%d/%s", connectionInfo.getPort(), connectionInfo.getDbName());
  }

  public static class TestDatabaseInfo {

    private final ConnectionInfo connectionInfo;
    private final DataSource dataSource;
    private final Jdbi jdbi;
    private final Optional<Flyway> flyway;

    private TestDatabaseInfo(
        final ConnectionInfo connectionInfo,
        final DataSource dataSource,
        final Jdbi jdbi,
        final Optional<Flyway> flyway) {
      this.connectionInfo = connectionInfo;
      this.dataSource = dataSource;
      this.jdbi = jdbi;
      this.flyway = flyway;
    }

    public String databaseUrl() {
      return DatabaseUtil.databaseUrl(connectionInfo);
    }

    public String databaseName() {
      return connectionInfo.getDbName();
    }

    public DataSource getDataSource() {
      return dataSource;
    }

    public Jdbi getJdbi() {
      return jdbi;
    }

    public Optional<Flyway> getFlyway() {
      return flyway;
    }
  }
}
