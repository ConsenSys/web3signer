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
package tech.pegasys.web3signer.dsl.utils;

import tech.pegasys.web3signer.slashingprotection.DbConnection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.ConnectionInfo;
import io.zonky.test.db.postgres.embedded.DatabasePreparer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.postgres.embedded.FlywayPreparer;
import io.zonky.test.db.postgres.embedded.PreparedDbProvider;
import org.jdbi.v3.core.Jdbi;

// This should only be used within the acceptance tests. This is copied from slashing-protection
// testFixtures as the gatling plugin does not support the use of testFixtures
public class DatabaseUtil {
  public static final String USERNAME = "postgres";
  public static final String PASSWORD = "postgres";
  public static final String MIGRATIONS_LOCATION = "/migrations/postgresql/";

  /**
   * Pooling is off: the Postgres cluster is shared by every test of the JVM, so a per-test pool
   * would keep its {@code minimumIdle} connections open for the rest of the run and exhaust the
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
   * One Postgres cluster per JVM, shared by every test of the fork. Each test gets its own database
   * cloned from the prepared template, so {@code initdb} runs once per fork instead of once per
   * test, and a test never sees another test's data.
   */
  private static final DatabasePreparer MIGRATED_TEMPLATE_PREPARER =
      FlywayPreparer.forClasspathLocation(MIGRATIONS_LOCATION);

  private static final int CLUSTER_START_ATTEMPTS = 3;

  private static volatile PreparedDbProvider databaseProvider;

  private static TestDatabaseInfo perTestDatabase;

  /**
   * Returns the database of the current test, creating it on first use. Repeated calls within one
   * test return the same database, so the signer processes a test starts all share it.
   */
  public static synchronized TestDatabaseInfo create() {
    if (perTestDatabase == null) {
      perTestDatabase = createDatabase();
    }
    return perTestDatabase;
  }

  /**
   * Drops the current test's database reference, so the next test starts from a freshly created
   * database. Called by {@code AcceptanceTestBase} around every test.
   */
  public static synchronized void reset() {
    perTestDatabase = null;
  }

  /**
   * Returns a database that is never memoized, so no other test can be handed it: for tests that
   * modify the schema and must not corrupt the database the rest of the JVM shares.
   */
  public static synchronized TestDatabaseInfo createIsolated() {
    return createDatabase();
  }

  private static TestDatabaseInfo createDatabase() {
    final PreparedDbProvider provider = databaseProvider();
    try {
      final ConnectionInfo connectionInfo = provider.createNewDatabase();
      final String databaseUrl = databaseUrl(connectionInfo);
      final DataSource dataSource = provider.createDataSourceFromConnectionInfo(connectionInfo);
      final Jdbi jdbi =
          DbConnection.createConnection(
              databaseUrl,
              DatabaseUtil.USERNAME,
              DatabaseUtil.PASSWORD,
              null,
              DB_CONNECTION_POOL_ENABLED);
      return new TestDatabaseInfo(connectionInfo, dataSource, jdbi);
    } catch (final SQLException e) {
      throw new UncheckedIOException(
          "Unable to create embedded postgres database", new IOException(e));
    }
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

  private static String databaseUrl(final ConnectionInfo connectionInfo) {
    return String.format(
        "jdbc:postgresql://localhost:%d/%s", connectionInfo.getPort(), connectionInfo.getDbName());
  }

  public static class TestDatabaseInfo {

    private final ConnectionInfo connectionInfo;
    private final DataSource dataSource;
    private final Jdbi jdbi;

    private TestDatabaseInfo(
        final ConnectionInfo connectionInfo, final DataSource dataSource, final Jdbi jdbi) {
      this.connectionInfo = connectionInfo;
      this.dataSource = dataSource;
      this.jdbi = jdbi;
    }

    public Jdbi getJdbi() {
      return jdbi;
    }

    public DataSource getDataSource() {
      return dataSource;
    }

    public String databaseUrl() {
      return DatabaseUtil.databaseUrl(connectionInfo);
    }

    public String databaseName() {
      return connectionInfo.getDbName();
    }
  }
}
