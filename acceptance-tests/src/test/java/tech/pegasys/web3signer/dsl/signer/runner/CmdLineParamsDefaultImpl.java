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
import static tech.pegasys.web3signer.signing.config.KeystoresParameters.KEYSTORES_PASSWORDS_PATH;
import static tech.pegasys.web3signer.signing.config.KeystoresParameters.KEYSTORES_PASSWORD_FILE;
import static tech.pegasys.web3signer.signing.config.KeystoresParameters.KEYSTORES_PATH;

import tech.pegasys.web3signer.commandline.PicoCliAzureKeyVaultParameters;
import tech.pegasys.web3signer.commandline.PicoCliGcpSecretManagerParameters;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.Pair;

public class CmdLineParamsDefaultImpl implements CmdLineParamsBuilder {
  private final SignerConfiguration signerConfig;
  private final Path dataPath;
  private Optional<String> slashingProtectionDbUrl = Optional.empty();

  public CmdLineParamsDefaultImpl(final SignerConfiguration signerConfig, final Path dataPath) {
    this.signerConfig = signerConfig;
    this.dataPath = dataPath;
  }

  @Override
  public List<String> createCmdLineParams() {
    final List<String> params = new ArrayList<>();
    params.add("--logging");
    params.add(signerConfig.logLevel());
    params.add("--http-listen-host");
    params.add(signerConfig.hostname());
    params.add("--http-listen-port");
    params.add(String.valueOf(signerConfig.httpPort()));
    if (!signerConfig.getHttpHostAllowList().isEmpty()) {
      params.add("--http-host-allowlist");
      params.add(String.join(",", signerConfig.getHttpHostAllowList()));
    }
    params.add("--key-config-path");
    params.add(signerConfig.getKeyStorePath().toString());
    if (signerConfig.isMetricsEnabled()) {
      params.add("--metrics-enabled");
      params.add("--metrics-port");
      params.add(Integer.toString(signerConfig.getMetricsPort()));
      if (!signerConfig.getMetricsHostAllowList().isEmpty()) {
        params.add("--metrics-host-allowlist");
        params.add(String.join(",", signerConfig.getMetricsHostAllowList()));
      }
      if (!signerConfig.getMetricsCategories().isEmpty()) {
        params.add("--metrics-category");
        params.add(String.join(",", signerConfig.getMetricsCategories()));
      }
    }

    params.add("--access-logs-enabled=true");

    if (signerConfig.isHttpDynamicPortAllocation()) {
      params.add("--data-path");
      params.add(dataPath.toAbsolutePath().toString());
    }

    params.addAll(createServerTlsArgs());

    params.add(signerConfig.getMode());

    if (signerConfig.getMode().equals("eth2")) {
      params.addAll(createEth2Args());

      if (signerConfig.getAzureKeyVaultParameters().isPresent()) {
        createAzureArgs(params);
      }
      if (signerConfig.getKeystoresParameters().isPresent()) {
        final KeystoresParameters keystoresParameters = signerConfig.getKeystoresParameters().get();
        params.add("--keystores-path");
        params.add(keystoresParameters.getKeystoresPath().toAbsolutePath().toString());
        if (keystoresParameters.getKeystoresPasswordsPath() != null) {
          params.add("--keystores-passwords-path");
          params.add(keystoresParameters.getKeystoresPasswordsPath().toAbsolutePath().toString());
        }
        if (keystoresParameters.getKeystoresPasswordFile() != null) {
          params.add("--keystores-password-file");
          params.add(keystoresParameters.getKeystoresPasswordFile().toAbsolutePath().toString());
        }
      }

      signerConfig
          .getAwsParameters()
          .ifPresent(awsParams -> params.addAll(awsSecretsManagerBulkLoadingOptions(awsParams)));
      signerConfig
          .getGcpParameters()
          .ifPresent(gcpParams -> params.addAll(gcpSecretManagerBulkLoadingOptions(gcpParams)));

      if (signerConfig.isSigningExtEnabled()) {
        params.add("--Xsigning-ext-enabled=true");
      }

      signerConfig
          .getCommitBoostParameters()
          .ifPresent(
              commitBoostParameters -> params.addAll(commitBoostOptions(commitBoostParameters)));

    } else if (signerConfig.getMode().equals("eth1")) {
      params.add("--downstream-http-port");
      params.add(Integer.toString(signerConfig.getDownstreamHttpPort()));
      params.add("--chain-id");
      params.add(Long.toString(signerConfig.getChainIdProvider().id()));
      params.addAll(createDownstreamTlsArgs());

      if (signerConfig.getAzureKeyVaultParameters().isPresent()) {
        createAzureArgs(params);
      }

      signerConfig
          .getV3KeystoresBulkloadParameters()
          .ifPresent(setV3KeystoresBulkloadParameters(params));
      signerConfig
          .getAwsParameters()
          .ifPresent(awsParams -> params.addAll(awsKmsBulkLoadingOptions(awsParams)));
    }

    return params;
  }

  private static List<String> commitBoostOptions(final Pair<Path, Path> commitBoostParameters) {
    return List.of(
        "--commit-boost-api-enabled=true",
        "--proxy-keystores-path",
        commitBoostParameters.getLeft().toAbsolutePath().toString(),
        "--proxy-keystores-password-file",
        commitBoostParameters.getRight().toAbsolutePath().toString());
  }

  private static Consumer<KeystoresParameters> setV3KeystoresBulkloadParameters(
      final List<String> params) {
    return keystoresParameters -> {
      params.add(KEYSTORES_PATH);
      params.add(keystoresParameters.getKeystoresPath().toAbsolutePath().toString());
      if (keystoresParameters.getKeystoresPasswordsPath() != null) {
        params.add(KEYSTORES_PASSWORDS_PATH);
        params.add(keystoresParameters.getKeystoresPasswordsPath().toAbsolutePath().toString());
      }
      if (keystoresParameters.getKeystoresPasswordFile() != null) {
        params.add(KEYSTORES_PASSWORD_FILE);
        params.add(keystoresParameters.getKeystoresPasswordFile().toAbsolutePath().toString());
      }
    };
  }

  @Override
  public Optional<String> slashingProtectionDbUrl() {
    return slashingProtectionDbUrl;
  }

  private Collection<? extends String> createServerTlsArgs() {
    final List<String> params = Lists.newArrayList();

    if (signerConfig.getServerTlsOptions().isPresent()) {
      final TlsOptions serverTlsOptions = signerConfig.getServerTlsOptions().get();
      params.add("--tls-keystore-file");
      params.add(serverTlsOptions.getKeyStoreFile().toString());
      params.add("--tls-keystore-password-file");
      params.add(serverTlsOptions.getKeyStorePasswordFile().toString());
      if (serverTlsOptions.getClientAuthConstraints().isEmpty()) {
        params.add("--tls-allow-any-client=true");
      } else {
        final ClientAuthConstraints constraints = serverTlsOptions.getClientAuthConstraints().get();
        if (constraints.getKnownClientsFile().isPresent()) {
          params.add("--tls-known-clients-file");
          params.add(constraints.getKnownClientsFile().get().toString());
        }
        if (constraints.isCaAuthorizedClientAllowed()) {
          params.add("--tls-allow-ca-clients=true");
        }
      }
    }
    return params;
  }

  private Collection<String> createDownstreamTlsArgs() {
    final Optional<ClientTlsOptions> optionalClientTlsOptions =
        signerConfig.getDownstreamTlsOptions();
    if (optionalClientTlsOptions.isEmpty()) {
      return Collections.emptyList();
    }

    final List<String> params = new ArrayList<>();
    params.add("--downstream-http-tls-enabled");

    final ClientTlsOptions clientTlsOptions = optionalClientTlsOptions.get();
    clientTlsOptions
        .getKeyStoreOptions()
        .ifPresent(
            pkcsStoreConfig -> {
              params.add("--downstream-http-tls-keystore-file");
              params.add(pkcsStoreConfig.getKeyStoreFile().toString());
              params.add("--downstream-http-tls-keystore-password-file");
              params.add(pkcsStoreConfig.getPasswordFile().toString());
            });

    if (clientTlsOptions.getKnownServersFile().isPresent()) {
      params.add("--downstream-http-tls-known-servers-file");
      params.add(clientTlsOptions.getKnownServersFile().get().toAbsolutePath().toString());
    }
    if (!clientTlsOptions.isCaAuthEnabled()) {
      params.add("--downstream-http-tls-ca-auth-enabled");
      params.add("false");
    }

    return Collections.unmodifiableCollection(params);
  }

  private Collection<String> createEth2Args() {
    final List<String> params = Lists.newArrayList();
    params.add("--slashing-protection-enabled");
    params.add(Boolean.toString(signerConfig.isSlashingProtectionEnabled()));

    if (signerConfig.isSlashingProtectionEnabled()) {
      slashingProtectionDbUrl =
          signerConfig
              .getSlashingProtectionDbUrl()
              .or(() -> Optional.of(DatabaseUtil.create().databaseUrl()));
      params.add("--slashing-protection-db-url");
      params.add(slashingProtectionDbUrl.get());
      params.add("--slashing-protection-db-username");
      params.add(signerConfig.getSlashingProtectionDbUsername());
      params.add("--slashing-protection-db-password");
      params.add(signerConfig.getSlashingProtectionDbPassword());
      if (signerConfig.getSlashingProtectionDbPoolConfigurationFile().isPresent()) {
        params.add("--slashing-protection-db-pool-configuration-file");
        params.add(signerConfig.getSlashingProtectionDbPoolConfigurationFile().toString());
      }

      // enabled by default, explicitly set when false
      if (!signerConfig.isSlashingProtectionDbConnectionPoolEnabled()) {
        params.add("--Xslashing-protection-db-connection-pool-enabled=false");
      }
    }

    params.addAll(createSubCommandArgs());

    if (signerConfig.isSlashingProtectionPruningEnabled()) {
      params.add("--slashing-protection-pruning-enabled");
      params.add(Boolean.toString(signerConfig.isSlashingProtectionPruningEnabled()));
      params.add("--slashing-protection-pruning-at-boot-enabled");
      params.add(Boolean.toString(signerConfig.isSlashingProtectionPruningAtBootEnabled()));
      params.add("--slashing-protection-pruning-epochs-to-keep");
      params.add(Long.toString(signerConfig.getSlashingProtectionPruningEpochsToKeep()));
      params.add("--slashing-protection-pruning-slots-per-epoch");
      params.add(Long.toString(signerConfig.getSlashingProtectionPruningSlotsPerEpoch()));
      params.add("--slashing-protection-pruning-interval");
      params.add(Long.toString(signerConfig.getSlashingProtectionPruningInterval()));
    }

    if (signerConfig.getAltairForkEpoch().isPresent()) {
      params.add("--Xnetwork-altair-fork-epoch");
      params.add(Long.toString(signerConfig.getAltairForkEpoch().get()));
    }

    if (signerConfig.getBellatrixForkEpoch().isPresent()) {
      params.add("--Xnetwork-bellatrix-fork-epoch");
      params.add(Long.toString(signerConfig.getBellatrixForkEpoch().get()));
    }

    if (signerConfig.getCapellaForkEpoch().isPresent()) {
      params.add("--Xnetwork-capella-fork-epoch");
      params.add(Long.toString(signerConfig.getCapellaForkEpoch().get()));
    }

    if (signerConfig.getDenebForkEpoch().isPresent()) {
      params.add("--Xnetwork-deneb-fork-epoch");
      params.add(Long.toString(signerConfig.getDenebForkEpoch().get()));
    }

    if (signerConfig.getElectraForkEpoch().isPresent()) {
      params.add("--Xnetwork-electra-fork-epoch");
      params.add(Long.toString(signerConfig.getElectraForkEpoch().get()));
    }

    if (signerConfig.getFuluForkEpoch().isPresent()) {
      params.add("--Xnetwork-fulu-fork-epoch");
      params.add(Long.toString(signerConfig.getFuluForkEpoch().get()));
    }

    if (signerConfig.getGloasForkEpoch().isPresent()) {
      params.add("--Xnetwork-gloas-fork-epoch");
      params.add(Long.toString(signerConfig.getGloasForkEpoch().get()));
    }

    if (signerConfig.getNetwork().isPresent()) {
      params.add("--network");
      params.add(signerConfig.getNetwork().get());
    }

    if (signerConfig.getGenesisState().isPresent()) {
      params.add("--genesis-state");
      params.add(signerConfig.getGenesisState().get());
    }

    if (signerConfig.isKeyManagerApiEnabled()) {
      params.add("--key-manager-api-enabled");
      params.add(Boolean.toString(signerConfig.isKeyManagerApiEnabled()));
    }

    return params;
  }

  private Collection<String> gcpSecretManagerBulkLoadingOptions(
      final GcpSecretManagerParameters gcpSecretManagerParameters) {
    final List<String> params = new ArrayList<>();
    params.add(
        PicoCliGcpSecretManagerParameters.GCP_SECRETS_ENABLED_OPTION
            + "="
            + gcpSecretManagerParameters.isEnabled());
    if (gcpSecretManagerParameters.getProjectId() != null) {
      params.add(
          PicoCliGcpSecretManagerParameters.GCP_PROJECT_ID_OPTION
              + "="
              + gcpSecretManagerParameters.getProjectId());
    }
    if (gcpSecretManagerParameters.getFilter().isPresent()) {
      params.add(
          PicoCliGcpSecretManagerParameters.GCP_SECRETS_FILTER_OPTION
              + "="
              + gcpSecretManagerParameters.getFilter().get());
    }
    return params;
  }

  private Collection<String> awsSecretsManagerBulkLoadingOptions(
      final AwsVaultParameters awsVaultParameters) {
    final List<String> params = new ArrayList<>();

    params.add(AWS_SECRETS_ENABLED_OPTION + "=" + awsVaultParameters.isEnabled());

    params.add(AWS_SECRETS_AUTH_MODE_OPTION);
    params.add(awsVaultParameters.getAuthenticationMode().name());

    if (awsVaultParameters.getAccessKeyId() != null) {
      params.add(AWS_SECRETS_ACCESS_KEY_ID_OPTION);
      params.add(awsVaultParameters.getAccessKeyId());
    }

    if (awsVaultParameters.getSecretAccessKey() != null) {
      params.add(AWS_SECRETS_SECRET_ACCESS_KEY_OPTION);
      params.add(awsVaultParameters.getSecretAccessKey());
    }

    if (awsVaultParameters.getRegion() != null) {
      params.add(AWS_SECRETS_REGION_OPTION);
      params.add(awsVaultParameters.getRegion());
    }

    awsVaultParameters
        .getEndpointOverride()
        .ifPresent(
            uri -> {
              params.add(AWS_ENDPOINT_OVERRIDE_OPTION);
              params.add(uri.toString());
            });

    if (!awsVaultParameters.getPrefixesFilter().isEmpty()) {
      params.add(AWS_SECRETS_PREFIXES_FILTER_OPTION);
      params.add(String.join(",", awsVaultParameters.getPrefixesFilter()));
    }

    awsVaultParameters
        .getTags()
        .forEach(
            (key, value) -> {
              params.add(AWS_SECRETS_TAG_OPTION);
              params.add(key + "=" + value);
            });

    return params;
  }

  private Collection<String> awsKmsBulkLoadingOptions(final AwsVaultParameters awsVaultParameters) {
    final List<String> params = new ArrayList<>();

    params.add(AWS_KMS_ENABLED_OPTION + "=" + awsVaultParameters.isEnabled());

    params.add(AWS_KMS_AUTH_MODE_OPTION);
    params.add(awsVaultParameters.getAuthenticationMode().name());

    if (awsVaultParameters.getAccessKeyId() != null) {
      params.add(AWS_KMS_ACCESS_KEY_ID_OPTION);
      params.add(awsVaultParameters.getAccessKeyId());
    }

    if (awsVaultParameters.getSecretAccessKey() != null) {
      params.add(AWS_KMS_SECRET_ACCESS_KEY_OPTION);
      params.add(awsVaultParameters.getSecretAccessKey());
    }

    if (awsVaultParameters.getRegion() != null) {
      params.add(AWS_KMS_REGION_OPTION);
      params.add(awsVaultParameters.getRegion());
    }

    awsVaultParameters
        .getEndpointOverride()
        .ifPresent(
            uri -> {
              params.add(AWS_ENDPOINT_OVERRIDE_OPTION);
              params.add(uri.toString());
            });

    awsVaultParameters
        .getTags()
        .forEach(
            (key, value) -> {
              params.add(AWS_KMS_TAG_OPTION);
              params.add(key + "=" + value);
            });

    return params;
  }

  private void createAzureArgs(final List<String> params) {
    final AzureKeyVaultParameters azureParams = signerConfig.getAzureKeyVaultParameters().get();
    params.add("--azure-vault-enabled=true");
    params.add("--azure-vault-auth-mode");
    params.add(azureParams.getAuthenticationMode().name());
    params.add("--azure-vault-name");
    params.add(azureParams.getKeyVaultName());
    params.add("--azure-client-id");
    params.add(azureParams.getClientId());
    params.add("--azure-client-secret");
    params.add(azureParams.getClientSecret());
    params.add("--azure-tenant-id");
    params.add(azureParams.getTenantId());

    azureParams
        .getTags()
        .forEach(
            (tagName, tagValue) -> {
              params.add("--azure-tags");
              params.add(tagName + "=" + tagValue);
            });

    azureParams
        .getAzureOverrides()
        .endpointOverride()
        .ifPresent(
            uri -> {
              params.add(PicoCliAzureKeyVaultParameters.AZURE_ENDPOINT_OVERRIDE_OPTION);
              params.add(uri.toString());
            });
    azureParams
        .getAzureOverrides()
        .authorityHostOverride()
        .ifPresent(
            uri -> {
              params.add(PicoCliAzureKeyVaultParameters.AZURE_AUTHORITY_HOST_OVERRIDE_OPTION);
              params.add(uri.toString());
            });
    azureParams
        .getAzureOverrides()
        .trustCertificateOverride()
        .ifPresent(
            path -> {
              params.add(PicoCliAzureKeyVaultParameters.AZURE_TRUST_CERTIFICATE_OVERRIDE_OPTION);
              params.add(path.toString());
            });
  }

  private List<String> createSubCommandArgs() {
    final List<String> params = new ArrayList<>();

    if (signerConfig.getSlashingExportPath().isPresent()) {
      params.add("export");
      params.add("--to");
      params.add(signerConfig.getSlashingExportPath().get().toAbsolutePath().toString());
    } else if (signerConfig.getSlashingImportPath().isPresent()) {
      params.add("import");
      params.add("--from");
      params.add(signerConfig.getSlashingImportPath().get().toAbsolutePath().toString());
    } else if (signerConfig.getWatermarkRepairParameters().isPresent()) {
      final WatermarkRepairParameters watermarkRepairParameters =
          signerConfig.getWatermarkRepairParameters().get();
      params.add("watermark-repair");
      if (watermarkRepairParameters.isRemoveHighWatermark()) {
        params.add("--remove-high-watermark=true");
      } else {
        params.add("--epoch");
        params.add(Long.toString(watermarkRepairParameters.getEpoch()));
        params.add("--slot");
        params.add(Long.toString(watermarkRepairParameters.getSlot()));
        if (watermarkRepairParameters.isSetHighWatermark()) {
          params.add("--set-high-watermark=true");
        }
      }
    }

    return params;
  }
}
