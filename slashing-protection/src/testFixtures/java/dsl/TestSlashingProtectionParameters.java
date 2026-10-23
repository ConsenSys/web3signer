/*
 * Copyright 2021 ConsenSys AG.
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
package dsl;

import tech.pegasys.web3signer.slashingprotection.SlashingProtectionParameters;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class TestSlashingProtectionParameters implements SlashingProtectionParameters {

  private final String dbUrl;
  private final String dbUser;
  private final String dbPassword;
  private final boolean pruningEnabled;
  private final boolean pruningAtBootEnabled;
  private final int pruningEpochsToKeep;
  private final int pruningSlotsPerEpoch;
  private final long pruningInterval;
  private final Path dbPoolConfigurationFile;

  private final Path pruningDbPoolConfigurationFile;

  private final int dbHealthCheckTimeoutMilliseconds;

  private final int dbHealthCheckIntervalMilliseconds;

  private final boolean dbConnectionPoolEnabled;

  public TestSlashingProtectionParameters(
      final String dbUrl, final String dbUser, final String dbPassword) {
    this(dbUrl, dbUser, dbPassword, 0, 0);
  }

  public TestSlashingProtectionParameters(
      final String dbUrl,
      final String dbUser,
      final String dbPassword,
      final int pruningEpochsToKeep,
      final int pruningSlotsPerEpoch) {
    this(dbUrl, dbUser, dbPassword, pruningEpochsToKeep, pruningSlotsPerEpoch, Long.MAX_VALUE);
  }

  public TestSlashingProtectionParameters(
      final String dbUrl,
      final String dbUser,
      final String dbPassword,
      final int pruningEpochsToKeep,
      final int pruningSlotsPerEpoch,
      final long pruningInterval) {
    this(
        dbUrl,
        dbUser,
        dbPassword,
        null,
        null,
        pruningEpochsToKeep,
        pruningSlotsPerEpoch,
        pruningInterval,
        3000,
        3000,
        // pooling off: tests share one Postgres cluster per JVM, so the connections a Hikari pool
        // keeps alive would accumulate across tests until the server's max_connections is reached
        false);
  }

  public TestSlashingProtectionParameters(
      final String dbUrl,
      final String dbUser,
      final String dbPassword,
      final Path dbPoolConfigurationFile,
      final Path pruningDbPoolConfigurationFile,
      final int pruningEpochsToKeep,
      final int pruningSlotsPerEpoch,
      final long pruningInterval,
      final int dbHealthCheckTimeoutMilliseconds,
      final int dbHealthCheckIntervalMilliseconds,
      final boolean dbConnectionPoolEnabled) {
    this.dbUrl = dbUrl;
    this.dbUser = dbUser;
    this.dbPassword = dbPassword;
    this.dbPoolConfigurationFile = dbPoolConfigurationFile;
    this.pruningDbPoolConfigurationFile = pruningDbPoolConfigurationFile;
    this.pruningEnabled = true;
    this.pruningAtBootEnabled = true;
    this.pruningEpochsToKeep = pruningEpochsToKeep;
    this.pruningSlotsPerEpoch = pruningSlotsPerEpoch;
    this.pruningInterval = pruningInterval;
    this.dbHealthCheckTimeoutMilliseconds = dbHealthCheckTimeoutMilliseconds;
    this.dbHealthCheckIntervalMilliseconds = dbHealthCheckIntervalMilliseconds;
    this.dbConnectionPoolEnabled = dbConnectionPoolEnabled;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public String getDbUrl() {
    return dbUrl;
  }

  @Override
  public String getDbUsername() {
    return dbUser;
  }

  @Override
  public String getDbPassword() {
    return dbPassword;
  }

  @Override
  public boolean isPruningEnabled() {
    return pruningEnabled;
  }

  @Override
  public long getPruningEpochsToKeep() {
    return pruningEpochsToKeep;
  }

  @Override
  public long getPruningSlotsPerEpoch() {
    return pruningSlotsPerEpoch;
  }

  @Override
  public long getPruningInterval() {
    return pruningInterval;
  }

  @Override
  public TimeUnit getPruningIntervalTimeUnit() {
    return TimeUnit.SECONDS;
  }

  @Override
  public Path getDbPoolConfigurationFile() {
    return dbPoolConfigurationFile;
  }

  @Override
  public Path getPruningDbPoolConfigurationFile() {
    return pruningDbPoolConfigurationFile;
  }

  @Override
  public boolean isPruningAtBootEnabled() {
    return pruningAtBootEnabled;
  }

  @Override
  public long getDbHealthCheckTimeoutMilliseconds() {
    return dbHealthCheckTimeoutMilliseconds;
  }

  @Override
  public long getDbHealthCheckIntervalMilliseconds() {
    return dbHealthCheckIntervalMilliseconds;
  }

  @Override
  public boolean isDbConnectionPoolEnabled() {
    return dbConnectionPoolEnabled;
  }
}
