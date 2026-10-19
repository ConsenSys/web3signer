/*
 * Copyright 2020 ConsenSys AG.
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
package tech.pegasys.web3signer.dsl.signer.runner;

import static tech.pegasys.web3signer.commandline.PicoCliAwsKmsParameters.AWS_KMS_ACCESS_KEY_ID_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsKmsParameters.AWS_KMS_AUTH_MODE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsKmsParameters.AWS_KMS_ENABLED_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsKmsParameters.AWS_KMS_REGION_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsKmsParameters.AWS_KMS_SECRET_ACCESS_KEY_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsKmsParameters.AWS_KMS_TAG_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_ENDPOINT_OVERRIDE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_ACCESS_KEY_ID_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_AUTH_MODE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_ENABLED_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_PREFIXES_FILTER_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_REGION_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_SECRET_ACCESS_KEY_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_TAG_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAzureKeyVaultParameters.AZURE_AUTHORITY_HOST_OVERRIDE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAzureKeyVaultParameters.AZURE_ENDPOINT_OVERRIDE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAzureKeyVaultParameters.AZURE_TRUST_CERTIFICATE_OVERRIDE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliGcpSecretManagerParameters.GCP_PROJECT_ID_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliGcpSecretManagerParameters.GCP_SECRETS_ENABLED_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliGcpSecretManagerParameters.GCP_SECRETS_FILTER_OPTION;
import static tech.pegasys.web3signer.signing.config.KeystoresParameters.KEYSTORES_PASSWORDS_PATH;
import static tech.pegasys.web3signer.signing.config.KeystoresParameters.KEYSTORES_PASSWORD_FILE;
import static tech.pegasys.web3signer.signing.config.KeystoresParameters.KEYSTORES_PATH;

import tech.pegasys.web3signer.commandline.valueprovider.PrefixUtil;
import tech.pegasys.web3signer.core.config.ClientAuthConstraints;
import tech.pegasys.web3signer.core.config.TlsOptions;
import tech.pegasys.web3signer.core.config.client.ClientTlsOptions;
import tech.pegasys.web3signer.dsl.signer.SignerConfiguration;
import tech.pegasys.web3signer.dsl.signer.WatermarkRepairParameters;
import tech.pegasys.web3signer.dsl.utils.DatabaseUtil;
import tech.pegasys.web3signer.signing.config.AwsVaultParameters;
import tech.pegasys.web3signer.signing.config.AzureKeyVaultParameters;
import tech.pegasys.web3signer.signing.config.GcpSecretManagerParameters;
import tech.pegasys.web3signer.signing.config.KeystoresParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;

public class CmdLineParamsConfigFileImpl implements CmdLineParamsBuilder {
  private static final ObjectMapper YAML_OBJECT_MAPPER =
      YAMLMapper.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build();
  private final SignerConfiguration signerConfig;
  private final Path dataPath;
  private Optional<String> slashingProtectionDbUrl = Optional.empty();

  public CmdLineParamsConfigFileImpl(final SignerConfiguration signerConfig, final Path dataPath) {
    this.signerConfig = signerConfig;
    this.dataPath = dataPath;
  }

  @Override
  public List<String> createCmdLineParams() {

    final ArrayList<String> params = new ArrayList<>();
    var yamlConfigMap = new HashMap<>();
    yamlConfigMap.put("logging", signerConfig.logLevel());
    yamlConfigMap.put("http-listen-host", signerConfig.hostname());
    yamlConfigMap.put("http-listen-port", signerConfig.httpPort());

    if (!signerConfig.getHttpHostAllowList().isEmpty()) {
      yamlConfigMap.put("http-host-allowlist", signerConfig.getHttpHostAllowList());
    }

    yamlConfigMap.put("key-config-path", signerConfig.getKeyStorePath().toString());

    if (signerConfig.isMetricsEnabled()) {
      yamlConfigMap.put("metrics-enabled", true);
      yamlConfigMap.put("metrics-port", signerConfig.getMetricsPort());
      if (!signerConfig.getMetricsHostAllowList().isEmpty()) {
        yamlConfigMap.put("metrics-host-allowlist", signerConfig.getMetricsHostAllowList());
      }
      if (!signerConfig.getMetricsCategories().isEmpty()) {
        yamlConfigMap.put("metrics-category", signerConfig.getMetricsCategories());
      }
    }

    yamlConfigMap.put("access-logs-enabled", true);

    if (signerConfig.isHttpDynamicPortAllocation()) {
      yamlConfigMap.put("data-path", dataPath.toAbsolutePath().toString());
    }

    yamlConfigMap.putAll(createServerTlsArgs());

    params.add(signerConfig.getMode()); // sub-command .. it can't go to config file

    if (signerConfig.getMode().equals("eth2")) {
      yamlConfigMap.putAll(createEth2SlashingProtectionArgs());

      if (signerConfig.getKeystoresParameters().isPresent()) {
        final KeystoresParameters keystoresParameters = signerConfig.getKeystoresParameters().get();
        yamlConfigMap.put(
            "eth2.keystores-path",
            keystoresParameters.getKeystoresPath().toAbsolutePath().toString());
        if (keystoresParameters.getKeystoresPasswordsPath() != null) {
          yamlConfigMap.put(
              "eth2.keystores-passwords-path",
              keystoresParameters.getKeystoresPasswordsPath().toAbsolutePath().toString());
        }
        if (keystoresParameters.getKeystoresPasswordFile() != null) {
          yamlConfigMap.put(
              "eth2.keystores-password-file",
              keystoresParameters.getKeystoresPasswordFile().toAbsolutePath().toString());
        }
      }

      signerConfig
          .getAwsParameters()
          .ifPresent(
              awsParams -> yamlConfigMap.putAll(awsSecretsManagerBulkLoadingOptions(awsParams)));
      signerConfig
          .getGcpParameters()
          .ifPresent(gcpParameters -> yamlConfigMap.putAll(gcpBulkLoadingOptions(gcpParameters)));

      signerConfig
          .getAzureKeyVaultParameters()
          .ifPresent(
              azureParams ->
                  yamlConfigMap.putAll(
                      azureBulkLoadingOptions(signerConfig.getMode(), azureParams)));

      if (signerConfig.isSigningExtEnabled()) {
        yamlConfigMap.put("eth2.Xsigning-ext-enabled", true);
      }

      signerConfig
          .getCommitBoostParameters()
          .ifPresent(
              commitBoostParameters ->
                  yamlConfigMap.putAll(commitBoostOptions(commitBoostParameters)));

      // add sub-sub command and its args
      final CommandArgs subCommandArgs = createSubCommandArgs();
      params.addAll(subCommandArgs.params);
      yamlConfigMap.putAll(subCommandArgs.yamlConfigMap);
    } else if (signerConfig.getMode().equals("eth1")) {
      yamlConfigMap.put("eth1.downstream-http-port", signerConfig.getDownstreamHttpPort());
      yamlConfigMap.put("eth1.chain-id", signerConfig.getChainIdProvider().id());
      yamlConfigMap.putAll(createDownstreamTlsArgs());

      signerConfig
          .getV3KeystoresBulkloadParameters()
          .ifPresent(
              keystoreParams -> yamlConfigMap.putAll(v3KeystoresBulkloadOptions(keystoreParams)));

      signerConfig
          .getAwsParameters()
          .ifPresent(awsParams -> yamlConfigMap.putAll(awsKmsBulkLoadingOptions(awsParams)));
    }

    signerConfig
        .getAzureKeyVaultParameters()
        .ifPresent(
            azureParams ->
                yamlConfigMap.putAll(azureBulkLoadingOptions(signerConfig.getMode(), azureParams)));

    // create temporary config file
    try {
      final Path configFile = Files.createTempFile("web3signer_config", ".yaml");
      FileUtils.forceDeleteOnExit(configFile.toFile());
      YAML_OBJECT_MAPPER.writeValue(configFile.toFile(), yamlConfigMap);
      params.addFirst(configFile.toAbsolutePath().toString());
      params.addFirst("--config-file");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return params;
  }

  private static Map<String, Object> commitBoostOptions(
      final Pair<Path, Path> commitBoostParameters) {
    var yamlConfigMap = new HashMap<String, Object>();
    yamlConfigMap.put("eth2.commit-boost-api-enabled", true);
    yamlConfigMap.put(
        "eth2.proxy-keystores-path", commitBoostParameters.getLeft().toAbsolutePath().toString());
    yamlConfigMap.put(
        "eth2.proxy-keystores-password-file",
        commitBoostParameters.getRight().toAbsolutePath().toString());
    return yamlConfigMap;
  }

  private Map<String, Object> v3KeystoresBulkloadOptions(
      final KeystoresParameters keystoresParameters) {
    var yamlConfigMap = new HashMap<String, Object>();
    yamlConfigMap.put(
        "eth1." + PrefixUtil.stripPrefix(KEYSTORES_PATH),
        keystoresParameters.getKeystoresPath().toAbsolutePath().toString());
    if (keystoresParameters.getKeystoresPasswordsPath() != null) {
      yamlConfigMap.put(
          "eth1." + PrefixUtil.stripPrefix(KEYSTORES_PASSWORDS_PATH),
          keystoresParameters.getKeystoresPasswordsPath().toAbsolutePath().toString());
    }
    if (keystoresParameters.getKeystoresPasswordFile() != null) {
      yamlConfigMap.put(
          "eth1." + PrefixUtil.stripPrefix(KEYSTORES_PASSWORD_FILE),
          keystoresParameters.getKeystoresPasswordFile().toAbsolutePath().toString());
    }

    return yamlConfigMap;
  }

  private Map<String, Object> azureBulkLoadingOptions(
      final String mode, final AzureKeyVaultParameters azureParams) {
    var yamlConfigMap = new HashMap<String, Object>();
    yamlConfigMap.put(mode + ".azure-vault-enabled", true);
    yamlConfigMap.put(mode + ".azure-vault-auth-mode", azureParams.getAuthenticationMode().name());
    yamlConfigMap.put(mode + ".azure-vault-name", azureParams.getKeyVaultName());
    yamlConfigMap.put(mode + ".azure-client-id", azureParams.getClientId());
    yamlConfigMap.put(mode + ".azure-client-secret", azureParams.getClientSecret());
    yamlConfigMap.put(mode + ".azure-tenant-id", azureParams.getTenantId());
    if (!azureParams.getTags().isEmpty()) {
      yamlConfigMap.put(mode + ".azure-tags", mapToStringConcatenated(azureParams.getTags()));
    }
    azureParams
        .getAzureOverrides()
        .endpointOverride()
        .ifPresent(
            uri ->
                yamlConfigMap.put(
                    mode + "." + PrefixUtil.stripPrefix(AZURE_ENDPOINT_OVERRIDE_OPTION),
                    uri.toString()));
    azureParams
        .getAzureOverrides()
        .authorityHostOverride()
        .ifPresent(
            uri ->
                yamlConfigMap.put(
                    mode + "." + PrefixUtil.stripPrefix(AZURE_AUTHORITY_HOST_OVERRIDE_OPTION),
                    uri.toString()));
    azureParams
        .getAzureOverrides()
        .trustCertificateOverride()
        .ifPresent(
            path ->
                yamlConfigMap.put(
                    mode + "." + PrefixUtil.stripPrefix(AZURE_TRUST_CERTIFICATE_OVERRIDE_OPTION),
                    path.toString()));
    return yamlConfigMap;
  }

  /**
   * Tags are key=value|key2=value2 format in the config file.
   *
   * @param map Tag Map
   * @return key=value|key2=value2
   */
  private String mapToStringConcatenated(final Map<String, String> map) {
    return map.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining("|"));
  }

  private CommandArgs createSubCommandArgs() {
    final List<String> params = new ArrayList<>();
    final Map<String, Object> yamlConfigMap = new HashMap<>();

    if (signerConfig.getSlashingExportPath().isPresent()) {
      params.add("export"); // sub-sub command
      yamlConfigMap.put(
          "eth2.export.to", signerConfig.getSlashingExportPath().get().toAbsolutePath().toString());
    } else if (signerConfig.getSlashingImportPath().isPresent()) {
      params.add("import"); // sub-sub command
      yamlConfigMap.put(
          "eth2.import.from",
          signerConfig.getSlashingImportPath().get().toAbsolutePath().toString());
    } else if (signerConfig.getWatermarkRepairParameters().isPresent()) {
      params.add("watermark-repair"); // sub-sub command
      final WatermarkRepairParameters watermarkRepairParameters =
          signerConfig.getWatermarkRepairParameters().get();
      if (watermarkRepairParameters.isRemoveHighWatermark()) {
        yamlConfigMap.put("eth2.watermark-repair.remove-high-watermark", true);
      } else {
        yamlConfigMap.put("eth2.watermark-repair.slot", watermarkRepairParameters.getSlot());
        yamlConfigMap.put("eth2.watermark-repair.epoch", watermarkRepairParameters.getEpoch());
        yamlConfigMap.put(
            "eth2.watermark-repair.set-high-watermark",
            watermarkRepairParameters.isSetHighWatermark());
      }
    }

    return new CommandArgs(params, yamlConfigMap);
  }

  @Override
  public Optional<String> slashingProtectionDbUrl() {
    return slashingProtectionDbUrl;
  }

  private Map<String, Object> createServerTlsArgs() {
    var yamlConfigMap = new HashMap<String, Object>();

    if (signerConfig.getServerTlsOptions().isPresent()) {
      final TlsOptions serverTlsOptions = signerConfig.getServerTlsOptions().get();
      yamlConfigMap.put("tls-keystore-file", serverTlsOptions.getKeyStoreFile().toString());
      yamlConfigMap.put(
          "tls-keystore-password-file", serverTlsOptions.getKeyStorePasswordFile().toString());

      if (serverTlsOptions.getClientAuthConstraints().isEmpty()) {
        yamlConfigMap.put("tls-allow-any-client", true);
      } else {
        final ClientAuthConstraints constraints = serverTlsOptions.getClientAuthConstraints().get();
        if (constraints.getKnownClientsFile().isPresent()) {
          yamlConfigMap.put(
              "tls-known-clients-file", constraints.getKnownClientsFile().get().getPath());
        }
        if (constraints.isCaAuthorizedClientAllowed()) {
          yamlConfigMap.put("tls-allow-ca-clients", true);
        }
      }
    }
    return yamlConfigMap;
  }

  private Map<String, Object> createDownstreamTlsArgs() {
    final Optional<ClientTlsOptions> optionalClientTlsOptions =
        signerConfig.getDownstreamTlsOptions();
    var yamlConfigMap = new HashMap<String, Object>();
    if (optionalClientTlsOptions.isEmpty()) {
      return yamlConfigMap;
    }

    final ClientTlsOptions clientTlsOptions = optionalClientTlsOptions.get();
    yamlConfigMap.put("eth1.downstream-http-tls-enabled", true);

    clientTlsOptions
        .getKeyStoreOptions()
        .ifPresent(
            pkcsStoreConfig -> {
              yamlConfigMap.put(
                  "eth1.downstream-http-tls-keystore-file",
                  pkcsStoreConfig.getKeyStoreFile().toString());
              yamlConfigMap.put(
                  "eth1.downstream-http-tls-keystore-password-file",
                  pkcsStoreConfig.getPasswordFile().toString());
            });

    if (clientTlsOptions.getKnownServersFile().isPresent()) {
      yamlConfigMap.put(
          "eth1.downstream-http-tls-known-servers-file",
          clientTlsOptions.getKnownServersFile().get().toAbsolutePath());
    }
    if (!clientTlsOptions.isCaAuthEnabled()) {
      yamlConfigMap.put("eth1.downstream-http-tls-ca-auth-enabled", false);
    }

    return yamlConfigMap;
  }

  private Map<String, Object> createEth2SlashingProtectionArgs() {
    var yamlConfigMap = new HashMap<String, Object>();
    yamlConfigMap.put(
        "eth2.slashing-protection-enabled", signerConfig.isSlashingProtectionEnabled());

    if (signerConfig.isSlashingProtectionEnabled()) {
      slashingProtectionDbUrl =
          signerConfig
              .getSlashingProtectionDbUrl()
              .or(() -> Optional.of(DatabaseUtil.create().databaseUrl()));
      yamlConfigMap.put("eth2.slashing-protection-db-url", slashingProtectionDbUrl.get());
      yamlConfigMap.put(
          "eth2.slashing-protection-db-username", signerConfig.getSlashingProtectionDbUsername());
      yamlConfigMap.put(
          "eth2.slashing-protection-db-password", signerConfig.getSlashingProtectionDbPassword());
      if (signerConfig.getSlashingProtectionDbPoolConfigurationFile().isPresent()) {
        yamlConfigMap.put(
            "eth2.slashing-protection-db-pool-configuration-file",
            signerConfig.getSlashingProtectionDbPoolConfigurationFile());
      }

      // enabled by default, explicitly set when false
      if (!signerConfig.isSlashingProtectionDbConnectionPoolEnabled()) {
        yamlConfigMap.put(
            "eth2.Xslashing-protection-db-connection-pool-enabled",
            signerConfig.isSlashingProtectionDbConnectionPoolEnabled());
      }
    }

    if (signerConfig.isSlashingProtectionPruningEnabled()) {
      yamlConfigMap.put(
          "eth2.slashing-protection-pruning-enabled",
          signerConfig.isSlashingProtectionPruningEnabled());
      yamlConfigMap.put(
          "eth2.slashing-protection-pruning-at-boot-enabled",
          signerConfig.isSlashingProtectionPruningAtBootEnabled());
      yamlConfigMap.put(
          "eth2.slashing-protection-pruning-epochs-to-keep",
          signerConfig.getSlashingProtectionPruningEpochsToKeep());
      yamlConfigMap.put(
          "eth2.slashing-protection-pruning-slots-per-epoch",
          signerConfig.getSlashingProtectionPruningSlotsPerEpoch());
      yamlConfigMap.put(
          "eth2.slashing-protection-pruning-interval",
          signerConfig.getSlashingProtectionPruningInterval());
    }

    if (signerConfig.getAltairForkEpoch().isPresent()) {
      yamlConfigMap.put("eth2.Xnetwork-altair-fork-epoch", signerConfig.getAltairForkEpoch().get());
    }

    if (signerConfig.getBellatrixForkEpoch().isPresent()) {
      yamlConfigMap.put(
          "eth2.Xnetwork-bellatrix-fork-epoch", signerConfig.getBellatrixForkEpoch().get());
    }

    if (signerConfig.getCapellaForkEpoch().isPresent()) {
      yamlConfigMap.put(
          "eth2.Xnetwork-capella-fork-epoch", signerConfig.getCapellaForkEpoch().get());
    }

    if (signerConfig.getDenebForkEpoch().isPresent()) {
      yamlConfigMap.put("eth2.Xnetwork-deneb-fork-epoch", signerConfig.getDenebForkEpoch().get());
    }

    if (signerConfig.getElectraForkEpoch().isPresent()) {
      yamlConfigMap.put(
          "eth2.Xnetwork-electra-fork-epoch", signerConfig.getElectraForkEpoch().get());
    }

    if (signerConfig.getFuluForkEpoch().isPresent()) {
      yamlConfigMap.put("eth2.Xnetwork-fulu-fork-epoch", signerConfig.getFuluForkEpoch().get());
    }

    if (signerConfig.getGloasForkEpoch().isPresent()) {
      yamlConfigMap.put("eth2.Xnetwork-gloas-fork-epoch", signerConfig.getGloasForkEpoch().get());
    }

    if (signerConfig.getNetwork().isPresent()) {
      yamlConfigMap.put("eth2.network", signerConfig.getNetwork().get());
    }

    if (signerConfig.getGenesisState().isPresent()) {
      yamlConfigMap.put("eth2.genesis-state", signerConfig.getGenesisState().get());
    }

    return yamlConfigMap;
  }

  private Map<String, Object> awsSecretsManagerBulkLoadingOptions(
      final AwsVaultParameters awsVaultParameters) {
    var yamlConfigMap = new HashMap<String, Object>();

    yamlConfigMap.put(
        "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_ENABLED_OPTION),
        awsVaultParameters.isEnabled());
    yamlConfigMap.put(
        "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_AUTH_MODE_OPTION),
        awsVaultParameters.getAuthenticationMode().name());

    if (awsVaultParameters.getAccessKeyId() != null) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_ACCESS_KEY_ID_OPTION),
          awsVaultParameters.getAccessKeyId());
    }

    if (awsVaultParameters.getSecretAccessKey() != null) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_SECRET_ACCESS_KEY_OPTION),
          awsVaultParameters.getSecretAccessKey());
    }

    if (awsVaultParameters.getRegion() != null) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_REGION_OPTION),
          awsVaultParameters.getRegion());
    }

    if (!awsVaultParameters.getPrefixesFilter().isEmpty()) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_PREFIXES_FILTER_OPTION),
          awsVaultParameters.getPrefixesFilter());
    }

    if (!awsVaultParameters.getTags().isEmpty()) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(AWS_SECRETS_TAG_OPTION),
          mapToStringConcatenated(awsVaultParameters.getTags()));
    }

    awsVaultParameters
        .getEndpointOverride()
        .ifPresent(
            uri ->
                yamlConfigMap.put(
                    "eth2." + PrefixUtil.stripPrefix(AWS_ENDPOINT_OVERRIDE_OPTION), uri));

    return yamlConfigMap;
  }

  private Map<String, Object> gcpBulkLoadingOptions(
      final GcpSecretManagerParameters gcpSecretManagerParameters) {
    var yamlConfigMap = new HashMap<String, Object>();
    yamlConfigMap.put(
        "eth2." + PrefixUtil.stripPrefix(GCP_SECRETS_ENABLED_OPTION),
        gcpSecretManagerParameters.isEnabled());

    if (gcpSecretManagerParameters.getProjectId() != null) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(GCP_PROJECT_ID_OPTION),
          gcpSecretManagerParameters.getProjectId());
    }
    if (gcpSecretManagerParameters.getFilter().isPresent()) {
      yamlConfigMap.put(
          "eth2." + PrefixUtil.stripPrefix(GCP_SECRETS_FILTER_OPTION),
          gcpSecretManagerParameters.getFilter().get());
    }
    return yamlConfigMap;
  }

  private Map<String, Object> awsKmsBulkLoadingOptions(
      final AwsVaultParameters awsVaultParameters) {
    var yamlConfigMap = new HashMap<String, Object>();
    yamlConfigMap.put(
        "eth1." + PrefixUtil.stripPrefix(AWS_KMS_ENABLED_OPTION), awsVaultParameters.isEnabled());
    yamlConfigMap.put(
        "eth1." + PrefixUtil.stripPrefix(AWS_KMS_AUTH_MODE_OPTION),
        awsVaultParameters.getAuthenticationMode().name());

    if (awsVaultParameters.getAccessKeyId() != null) {
      yamlConfigMap.put(
          "eth1." + PrefixUtil.stripPrefix(AWS_KMS_ACCESS_KEY_ID_OPTION),
          awsVaultParameters.getAccessKeyId());
    }

    if (awsVaultParameters.getSecretAccessKey() != null) {
      yamlConfigMap.put(
          "eth1." + PrefixUtil.stripPrefix(AWS_KMS_SECRET_ACCESS_KEY_OPTION),
          awsVaultParameters.getSecretAccessKey());
    }

    if (awsVaultParameters.getRegion() != null) {
      yamlConfigMap.put(
          "eth1." + PrefixUtil.stripPrefix(AWS_KMS_REGION_OPTION), awsVaultParameters.getRegion());
    }

    if (!awsVaultParameters.getTags().isEmpty()) {
      yamlConfigMap.put(
          "eth1." + PrefixUtil.stripPrefix(AWS_KMS_TAG_OPTION),
          mapToStringConcatenated(awsVaultParameters.getTags()));
    }

    awsVaultParameters
        .getEndpointOverride()
        .ifPresent(
            uri ->
                yamlConfigMap.put(
                    "eth1." + PrefixUtil.stripPrefix(AWS_ENDPOINT_OVERRIDE_OPTION), uri));

    return yamlConfigMap;
  }

  private record CommandArgs(List<String> params, Map<String, Object> yamlConfigMap) {}
}
