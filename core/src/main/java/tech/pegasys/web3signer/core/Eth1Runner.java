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
package tech.pegasys.web3signer.core;

import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_AWS_BULK_LOADING;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_AZURE_BULK_LOADING;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_V3_KEYSTORES_BULK_LOADING;

import tech.pegasys.web3signer.core.config.BaseConfig;
import tech.pegasys.web3signer.core.config.Eth1Config;
import tech.pegasys.web3signer.core.routes.PublicKeysListRoute;
import tech.pegasys.web3signer.core.routes.ReloadRoute;
import tech.pegasys.web3signer.core.routes.eth1.Eth1SignRoute;
import tech.pegasys.web3signer.core.routes.eth1.JsonRpcRoute;
import tech.pegasys.web3signer.keystorage.azure.AzureKeyVault;
import tech.pegasys.web3signer.keystorage.hashicorp.HashicorpConnectionFactory;
import tech.pegasys.web3signer.signing.ArtifactSignerProvider;
import tech.pegasys.web3signer.signing.EthSecpArtifactSigner;
import tech.pegasys.web3signer.signing.bulkloading.SecpAwsBulkLoader;
import tech.pegasys.web3signer.signing.bulkloading.SecpAzureBulkLoader;
import tech.pegasys.web3signer.signing.bulkloading.SecpV3KeystoresBulkLoader;
import tech.pegasys.web3signer.signing.config.AzureKeyVaultFactory;
import tech.pegasys.web3signer.signing.config.AzureKeyVaultParameters;
import tech.pegasys.web3signer.signing.config.DefaultArtifactSignerProvider;
import tech.pegasys.web3signer.signing.config.KeystoresParameters;
import tech.pegasys.web3signer.signing.config.SecpArtifactSignerProviderAdapter;
import tech.pegasys.web3signer.signing.config.SignerLoader;
import tech.pegasys.web3signer.signing.config.SignerSourceResult;
import tech.pegasys.web3signer.signing.config.metadata.Secp256k1ArtifactSignerFactory;
import tech.pegasys.web3signer.signing.config.metadata.parser.SignerParser;
import tech.pegasys.web3signer.signing.config.metadata.parser.YamlMapperFactory;
import tech.pegasys.web3signer.signing.config.metadata.parser.YamlSignerParser;
import tech.pegasys.web3signer.signing.secp256k1.aws.AwsKmsSignerFactory;
import tech.pegasys.web3signer.signing.secp256k1.aws.CachedAwsKmsClientFactory;
import tech.pegasys.web3signer.signing.secp256k1.azure.AzureKeyVaultSignerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.plugin.services.MetricsSystem;

/**
 * Runner subclass for eth1 mode
 *
 * <p>eth1 mode uses two secp signer providers. For signing operations /api/v1/eth1/sign/, the
 * secp256k1 primary key is used as identifier. For JSON-RPC call (eth_signTransactions etc.), the
 * eth1 address is used as identifier. See createArtifactSignerProvider method.
 *
 * @see SecpArtifactSignerProviderAdapter that uses existing ArtifactSignerProvideer maps eth1
 *     address to primary key.
 */
public class Eth1Runner extends Runner {
  private static final Logger LOG = LogManager.getLogger();
  private final Eth1Config eth1Config;

  public Eth1Runner(final BaseConfig baseConfig, final Eth1Config eth1Config) {
    super(baseConfig);
    this.eth1Config = eth1Config;
  }

  @Override
  protected void populateRouter(final Context context) {
    new PublicKeysListRoute(context, "eth1").register();
    new Eth1SignRoute(context).register();
    new ReloadRoute(context).register();
    new JsonRpcRoute(context, eth1Config).register();
  }

  @Override
  protected List<ArtifactSignerProvider> createArtifactSignerProvider(
      final MetricsSystem metricsSystem) {
    // Create factories ONCE at startup
    final AzureKeyVaultFactory azureKeyVaultFactory = new AzureKeyVaultFactory();
    final CachedAwsKmsClientFactory cachedAwsKmsClientFactory =
        new CachedAwsKmsClientFactory(eth1Config.getAwsKmsClientCacheSize());
    final SignerLoader signerLoader = new SignerLoader(baseConfig.getSignerLoaderConfig());

    // Register ALL for cleanup ONCE
    registerClose(azureKeyVaultFactory);
    registerClose(cachedAwsKmsClientFactory);
    registerClose(signerLoader);

    // Create signer factories that use the shared instances
    final AzureKeyVaultSignerFactory azureSignerFactory =
        new AzureKeyVaultSignerFactory(azureKeyVaultFactory);
    final AwsKmsSignerFactory awsKmsSignerFactory =
        new AwsKmsSignerFactory(cachedAwsKmsClientFactory, true);

    final ArtifactSignerProvider signerProvider =
        new DefaultArtifactSignerProvider(
            () -> {
              // Supplier reuses the same factory instances on every reload
              final List<SignerSourceResult> results = new ArrayList<>();
              results.add(
                  loadSignersFromKeyConfigFiles(
                      azureKeyVaultFactory, azureSignerFactory, awsKmsSignerFactory, signerLoader));
              results.addAll(
                  bulkLoadSigners(
                      azureKeyVaultFactory,
                      azureSignerFactory,
                      cachedAwsKmsClientFactory,
                      awsKmsSignerFactory));

              return results;
            },
            Optional.empty(),
            Optional.empty());

    // uses eth1 address as identifier
    final ArtifactSignerProvider secpArtifactSignerProvider =
        new SecpArtifactSignerProviderAdapter(signerProvider);

    // this order DO matter for reload handler
    return List.of(signerProvider, secpArtifactSignerProvider);
  }

  private SignerSourceResult loadSignersFromKeyConfigFiles(
      final AzureKeyVaultFactory azureKeyVaultFactory,
      final AzureKeyVaultSignerFactory azureSignerFactory,
      final AwsKmsSignerFactory awsKmsSignerFactory,
      final SignerLoader signerLoader) {
    try (final HashicorpConnectionFactory hashicorpConnectionFactory =
        new HashicorpConnectionFactory()) {
      final Secp256k1ArtifactSignerFactory ethSecpArtifactSignerFactory =
          new Secp256k1ArtifactSignerFactory(
              hashicorpConnectionFactory,
              baseConfig.getKeyConfigPath(),
              azureSignerFactory,
              EthSecpArtifactSigner::new,
              azureKeyVaultFactory,
              awsKmsSignerFactory,
              true);

      final SignerParser signerParser =
          new YamlSignerParser(
              List.of(ethSecpArtifactSignerFactory),
              YamlMapperFactory.createYamlMapper(baseConfig.getKeyStoreConfigFileMaxSize()));

      return signerLoader.load(signerParser);
    }
  }

  private List<SignerSourceResult> bulkLoadSigners(
      final AzureKeyVaultFactory azureKeyVaultFactory,
      final AzureKeyVaultSignerFactory azureSignerFactory,
      final CachedAwsKmsClientFactory cachedAwsKmsClientFactory,
      final AwsKmsSignerFactory awsKmsSignerFactory) {
    final List<SignerSourceResult> results = new ArrayList<>();
    if (eth1Config.getAzureKeyVaultConfig().isAzureKeyVaultEnabled()) {
      results.add(bulkLoadAzureKeys(azureKeyVaultFactory, azureSignerFactory));
    }
    if (eth1Config.getAwsVaultParameters().isEnabled()) {
      results.add(bulkLoadAwsKeys(cachedAwsKmsClientFactory, awsKmsSignerFactory));
    }

    // v3 bulk loading
    if (eth1Config.getV3KeystoresBulkLoadParameters().isEnabled()) {
      results.add(bulkloadV3Keystores());
    }

    return results;
  }

  private SignerSourceResult bulkLoadAzureKeys(
      AzureKeyVaultFactory azureKeyVaultFactory, AzureKeyVaultSignerFactory azureSignerFactory) {
    LOG.info("Bulk loading keys from Azure key vault ... ");
    final AzureKeyVaultParameters azureKeyVaultConfig = eth1Config.getAzureKeyVaultConfig();
    final AzureKeyVault azureKeyVault =
        azureKeyVaultFactory.createAzureKeyVault(
            azureKeyVaultConfig.getClientId(),
            azureKeyVaultConfig.getClientSecret(),
            azureKeyVaultConfig.getKeyVaultName(),
            azureKeyVaultConfig.getTenantId(),
            azureKeyVaultConfig.getAuthenticationMode(),
            azureKeyVaultConfig.getTimeout(),
            azureKeyVaultConfig.getAzureOverrides());
    final SecpAzureBulkLoader secpAzureBulkLoader =
        new SecpAzureBulkLoader(azureKeyVault, azureSignerFactory);
    final SignerSourceResult azureResult =
        SignerSourceResult.fromVault(
            "Azure key vault", secpAzureBulkLoader.load(azureKeyVaultConfig));
    LOG.info(
        "Keys loaded from Azure: [{}], with error count: [{}]",
        azureResult.signers().size(),
        azureResult.errorCount());
    registerSignerLoadingHealthCheck(KEYS_CHECK_AZURE_BULK_LOADING, azureResult);
    return azureResult;
  }

  private SignerSourceResult bulkLoadAwsKeys(
      CachedAwsKmsClientFactory cachedAwsKmsClientFactory,
      AwsKmsSignerFactory awsKmsSignerFactory) {
    LOG.info("Bulk loading keys from AWS KMS key vault ... ");
    final SecpAwsBulkLoader secpAwsBulkLoader =
        new SecpAwsBulkLoader(cachedAwsKmsClientFactory, awsKmsSignerFactory);
    final SignerSourceResult awsResult =
        SignerSourceResult.fromVault(
            "AWS KMS", secpAwsBulkLoader.load(eth1Config.getAwsVaultParameters()));
    LOG.info(
        "Keys loaded from AWS: [{}], with error count: [{}]",
        awsResult.signers().size(),
        awsResult.errorCount());
    registerSignerLoadingHealthCheck(KEYS_CHECK_AWS_BULK_LOADING, awsResult);
    return awsResult;
  }

  private SignerSourceResult bulkloadV3Keystores() {
    final KeystoresParameters v3WalletBLParams = eth1Config.getV3KeystoresBulkLoadParameters();

    LOG.info("Bulk loading v3 keystore files ... ");
    final SignerSourceResult walletResults =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(
            v3WalletBLParams.getKeystoresPath(),
            v3WalletBLParams.hasKeystoresPasswordFile()
                ? v3WalletBLParams.getKeystoresPasswordFile()
                : v3WalletBLParams.getKeystoresPasswordsPath());
    LOG.info(
        "Keys loaded from v3 keystores files: [{}], with error count: [{}]",
        walletResults.signers().size(),
        walletResults.errorCount());
    registerSignerLoadingHealthCheck(KEYS_CHECK_V3_KEYSTORES_BULK_LOADING, walletResults);
    return walletResults;
  }

  private void registerSignerLoadingHealthCheck(
      final String name, final SignerSourceResult result) {
    super.registerHealthCheckProcedure(
        name,
        promise -> {
          final JsonObject statusJson =
              new JsonObject()
                  .put("keys-loaded", result.signers().size())
                  .put("error-count", result.errorCount());
          promise.complete(result.errorCount() > 0 ? Status.KO(statusJson) : Status.OK(statusJson));
        });
  }
}
