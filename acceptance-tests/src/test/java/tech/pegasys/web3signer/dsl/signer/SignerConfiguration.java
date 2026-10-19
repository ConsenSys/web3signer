/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.web3signer.dsl.signer;

import tech.pegasys.web3signer.core.config.TlsOptions;
import tech.pegasys.web3signer.core.config.client.ClientTlsOptions;
import tech.pegasys.web3signer.core.service.jsonrpc.handlers.signing.ChainIdProvider;
import tech.pegasys.web3signer.dsl.tls.TlsCertificateDefinition;
import tech.pegasys.web3signer.signing.config.AwsVaultParameters;
import tech.pegasys.web3signer.signing.config.AzureKeyVaultParameters;
import tech.pegasys.web3signer.signing.config.GcpSecretManagerParameters;
import tech.pegasys.web3signer.signing.config.KeystoresParameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;

public class SignerConfiguration {

  public static final int UNASSIGNED_PORT = 0;

  private final String hostname;
  private final Level logLevel;
  private final int httpRpcPort;
  private final List<String> httpHostAllowList;
  private final Path keyStorePath;
  private final List<String> metricsHostAllowList;
  private final List<String> metricsCategories;
  private final boolean metricsEnabled;
  private final Optional<AzureKeyVaultParameters> azureKeyVaultParameters;
  private final Optional<AwsVaultParameters> awsSecretsManagerParameters;
  private final Optional<GcpSecretManagerParameters> gcpSecretManagerParameters;
  private final Optional<KeystoresParameters> keystoresParameters;
  private final Optional<TlsOptions> serverTlsOptions;
  private final Optional<TlsCertificateDefinition> overriddenCaTrustStore;
  private final int metricsPort;
  private final String mode;
  private final Optional<String> slashingProtectionDbUrl;
  private final String slashingProtectionDbUsername;
  private final String slashingProtectionDbPassword;
  private final boolean slashingProtectionDbConnectionPoolEnabled;
  private final Optional<Path> slashingProtectionDbPoolConfigurationFile;
  private final Optional<Map<String, String>> web3SignerEnvironment;
  private final boolean enableSlashing;
  private final Optional<Path> slashingExportPath;
  private final Optional<Path> slashingImportPath;
  private final boolean enableSlashingPruning;
  private final boolean enableSlashingPruningAtBoot;
  private final boolean useConfigFile;
  private final long slashingPruningEpochsToKeep;
  private final long slashingPruningSlotsPerEpoch;
  private final long slashingPruningInterval;
  private final Optional<Long> altairForkEpoch;

  private final Optional<Long> bellatrixForkEpoch;
  private final Optional<Long> capellaForkEpoch;
  private final Optional<Long> denebForkEpoch;
  private final Optional<Long> electraForkEpoch;
  private final Optional<Long> fuluForkEpoch;
  private final Optional<Long> gloasForkEpoch;
  private final Optional<String> network;
  private final Optional<String> genesisState;
  private final boolean keyManagerApiEnabled;
  private Optional<WatermarkRepairParameters> watermarkRepairParameters;
  private int downstreamHttpPort;
  private Optional<ClientTlsOptions> downstreamTlsOptions;
  private final Duration startupTimeout;
  private final ChainIdProvider chainIdProvider;
  private final Optional<KeystoresParameters> v3KeystoresBulkloadParameters;

  private final boolean signingExtEnabled;
  private Optional<Pair<Path, Path>> commitBoostParameters;

  public SignerConfiguration(
      final String hostname,
      final int httpRpcPort,
      final Level logLevel,
      final List<String> httpHostAllowList,
      final Path keyStorePath,
      final int metricsPort,
      final List<String> metricsHostAllowList,
      final List<String> metricsCategories,
      final boolean metricsEnabled,
      final Optional<AzureKeyVaultParameters> azureKeyVaultParameters,
      final Optional<AwsVaultParameters> awsSecretsManagerParameters,
      final Optional<GcpSecretManagerParameters> gcpSecretManagerParameters,
      final Optional<KeystoresParameters> keystoresParameters,
      final Optional<TlsOptions> serverTlsOptions,
      final Optional<TlsCertificateDefinition> overriddenCaTrustStore,
      final Optional<String> slashingProtectionDbUrl,
      final String slashingProtectionDbUsername,
      final String slashingProtectionDbPassword,
      final boolean slashingProtectionDbConnectionPoolEnabled,
      final String mode,
      final Optional<Map<String, String>> web3SignerEnvironment,
      final Duration startupTimeout,
      final boolean enableSlashing,
      final Optional<Path> slashingExportPath,
      final Optional<Path> slashingImportPath,
      final boolean enableSlashingPruning,
      final boolean enableSlashingPruningAtBoot,
      final long slashingPruningEpochsToKeep,
      final long slashingPruningSlotsPerEpoch,
      final long slashingPruningSchedule,
      final boolean useConfigFile,
      final Optional<Path> slashingDbPoolConfigurationFile,
      final Optional<Long> altairForkEpoch,
      final Optional<Long> bellatrixForkEpoch,
      final Optional<Long> capellaForkEpoch,
      final Optional<Long> denebForkEpoch,
      final Optional<Long> electraForkEpoch,
      final Optional<Long> fuluForkEpoch,
      final Optional<Long> gloasForkEpoch,
      final Optional<String> network,
      final Optional<String> genesisState,
      final boolean keyManagerApiEnabled,
      final Optional<WatermarkRepairParameters> watermarkRepairParameters,
      final int downstreamHttpPort,
      final Optional<ClientTlsOptions> downstreamTlsOptions,
      final ChainIdProvider chainIdProvider,
      final Optional<KeystoresParameters> v3KeystoresBulkloadParameters,
      final boolean signingExtEnabled,
      final Optional<Pair<Path, Path>> commitBoostParameters) {
    this.hostname = hostname;
    this.logLevel = logLevel;
    this.httpRpcPort = httpRpcPort;
    this.httpHostAllowList = httpHostAllowList;
    this.keyStorePath = keyStorePath;
    this.metricsPort = metricsPort;
    this.metricsHostAllowList = metricsHostAllowList;
    this.metricsCategories = metricsCategories;
    this.metricsEnabled = metricsEnabled;
    this.azureKeyVaultParameters = azureKeyVaultParameters;
    this.awsSecretsManagerParameters = awsSecretsManagerParameters;
    this.gcpSecretManagerParameters = gcpSecretManagerParameters;
    this.keystoresParameters = keystoresParameters;
    this.serverTlsOptions = serverTlsOptions;
    this.overriddenCaTrustStore = overriddenCaTrustStore;
    this.slashingProtectionDbUrl = slashingProtectionDbUrl;
    this.slashingProtectionDbUsername = slashingProtectionDbUsername;
    this.slashingProtectionDbPassword = slashingProtectionDbPassword;
    this.slashingProtectionDbConnectionPoolEnabled = slashingProtectionDbConnectionPoolEnabled;
    this.mode = mode;
    this.web3SignerEnvironment = web3SignerEnvironment;
    this.startupTimeout = startupTimeout;
    this.enableSlashing = enableSlashing;
    this.slashingExportPath = slashingExportPath;
    this.slashingImportPath = slashingImportPath;
    this.enableSlashingPruning = enableSlashingPruning;
    this.enableSlashingPruningAtBoot = enableSlashingPruningAtBoot;
    this.slashingPruningEpochsToKeep = slashingPruningEpochsToKeep;
    this.slashingPruningSlotsPerEpoch = slashingPruningSlotsPerEpoch;
    this.slashingPruningInterval = slashingPruningSchedule;
    this.useConfigFile = useConfigFile;
    this.slashingProtectionDbPoolConfigurationFile = slashingDbPoolConfigurationFile;
    this.altairForkEpoch = altairForkEpoch;
    this.bellatrixForkEpoch = bellatrixForkEpoch;
    this.capellaForkEpoch = capellaForkEpoch;
    this.denebForkEpoch = denebForkEpoch;
    this.electraForkEpoch = electraForkEpoch;
    this.fuluForkEpoch = fuluForkEpoch;
    this.gloasForkEpoch = gloasForkEpoch;
    this.network = network;
    this.genesisState = genesisState;
    this.keyManagerApiEnabled = keyManagerApiEnabled;
    this.watermarkRepairParameters = watermarkRepairParameters;
    this.downstreamHttpPort = downstreamHttpPort;
    this.downstreamTlsOptions = downstreamTlsOptions;
    this.chainIdProvider = chainIdProvider;
    this.v3KeystoresBulkloadParameters = v3KeystoresBulkloadParameters;
    this.signingExtEnabled = signingExtEnabled;
    this.commitBoostParameters = commitBoostParameters;
  }

  public String hostname() {
    return hostname;
  }

  public int httpPort() {
    return httpRpcPort;
  }

  public String logLevel() {
    return logLevel.toString();
  }

  public List<String> getHttpHostAllowList() {
    return httpHostAllowList;
  }

  public Path getKeyStorePath() {
    return keyStorePath;
  }

  public boolean isHttpDynamicPortAllocation() {
    return httpRpcPort == UNASSIGNED_PORT;
  }

  public boolean isMetricsEnabled() {
    return metricsEnabled;
  }

  public int getMetricsPort() {
    return metricsPort;
  }

  public List<String> getMetricsHostAllowList() {
    return metricsHostAllowList;
  }

  public List<String> getMetricsCategories() {
    return metricsCategories;
  }

  public Optional<TlsOptions> getServerTlsOptions() {
    return serverTlsOptions;
  }

  public Optional<TlsCertificateDefinition> getOverriddenCaTrustStore() {
    return overriddenCaTrustStore;
  }

  public Optional<AzureKeyVaultParameters> getAzureKeyVaultParameters() {
    return azureKeyVaultParameters;
  }

  public Optional<AwsVaultParameters> getAwsParameters() {
    return awsSecretsManagerParameters;
  }

  public Optional<GcpSecretManagerParameters> getGcpParameters() {
    return gcpSecretManagerParameters;
  }

  public Optional<KeystoresParameters> getKeystoresParameters() {
    return keystoresParameters;
  }

  public boolean isMetricsDynamicPortAllocation() {
    return metricsPort == UNASSIGNED_PORT;
  }

  public String getMode() {
    return mode;
  }

  public Optional<String> getSlashingProtectionDbUrl() {
    return slashingProtectionDbUrl;
  }

  public boolean isSlashingProtectionEnabled() {
    return enableSlashing;
  }

  public String getSlashingProtectionDbUsername() {
    return slashingProtectionDbUsername;
  }

  public String getSlashingProtectionDbPassword() {
    return slashingProtectionDbPassword;
  }

  public Optional<Map<String, String>> getWeb3SignerEnvironment() {
    return web3SignerEnvironment;
  }

  public Optional<Path> getSlashingExportPath() {
    return slashingExportPath;
  }

  public Optional<Path> getSlashingImportPath() {
    return slashingImportPath;
  }

  public boolean isSlashingProtectionPruningEnabled() {
    return enableSlashingPruning;
  }

  public boolean isSlashingProtectionPruningAtBootEnabled() {
    return enableSlashingPruningAtBoot;
  }

  public long getSlashingProtectionPruningEpochsToKeep() {
    return slashingPruningEpochsToKeep;
  }

  public long getSlashingProtectionPruningSlotsPerEpoch() {
    return slashingPruningSlotsPerEpoch;
  }

  public long getSlashingProtectionPruningInterval() {
    return slashingPruningInterval;
  }

  public boolean useConfigFile() {
    return useConfigFile;
  }

  public Optional<Path> getSlashingProtectionDbPoolConfigurationFile() {
    return slashingProtectionDbPoolConfigurationFile;
  }

  public Optional<Long> getAltairForkEpoch() {
    return altairForkEpoch;
  }

  public Optional<Long> getBellatrixForkEpoch() {
    return bellatrixForkEpoch;
  }

  public Optional<Long> getCapellaForkEpoch() {
    return capellaForkEpoch;
  }

  public Optional<Long> getDenebForkEpoch() {
    return denebForkEpoch;
  }

  public Optional<Long> getElectraForkEpoch() {
    return electraForkEpoch;
  }

  public Optional<Long> getFuluForkEpoch() {
    return fuluForkEpoch;
  }

  public Optional<Long> getGloasForkEpoch() {
    return gloasForkEpoch;
  }

  public Optional<String> getNetwork() {
    return network;
  }

  public Optional<String> getGenesisState() {
    return genesisState;
  }

  public boolean isKeyManagerApiEnabled() {
    return keyManagerApiEnabled;
  }

  public Duration getStartupTimeout() {
    return startupTimeout;
  }

  public boolean isSlashingProtectionDbConnectionPoolEnabled() {
    return slashingProtectionDbConnectionPoolEnabled;
  }

  public Optional<WatermarkRepairParameters> getWatermarkRepairParameters() {
    return watermarkRepairParameters;
  }

  public int getDownstreamHttpPort() {
    return downstreamHttpPort;
  }

  public Optional<ClientTlsOptions> getDownstreamTlsOptions() {
    return downstreamTlsOptions;
  }

  public ChainIdProvider getChainIdProvider() {
    return chainIdProvider;
  }

  public Optional<KeystoresParameters> getV3KeystoresBulkloadParameters() {
    return v3KeystoresBulkloadParameters;
  }

  public boolean isSigningExtEnabled() {
    return signingExtEnabled;
  }

  public Optional<Pair<Path, Path>> getCommitBoostParameters() {
    return commitBoostParameters;
  }
}
