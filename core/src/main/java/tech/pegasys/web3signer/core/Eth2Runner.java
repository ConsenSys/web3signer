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
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_CONFIG_FILE_LOADING;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_GCP_BULK_LOADING;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_KEYSTORE_BULK_LOADING;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.SLASHING_PROTECTION_DB;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSSecretKey;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.web3signer.core.config.BaseConfig;
import tech.pegasys.web3signer.core.config.KeyManagerApiConfig;
import tech.pegasys.web3signer.core.routes.PublicKeysListRoute;
import tech.pegasys.web3signer.core.routes.ReloadRoute;
import tech.pegasys.web3signer.core.routes.eth2.CommitBoostGenerateProxyKeyRoute;
import tech.pegasys.web3signer.core.routes.eth2.CommitBoostPublicKeysRoute;
import tech.pegasys.web3signer.core.routes.eth2.CommitBoostRequestSignatureRoute;
import tech.pegasys.web3signer.core.routes.eth2.Eth2SignExtensionRoute;
import tech.pegasys.web3signer.core.routes.eth2.Eth2SignRoute;
import tech.pegasys.web3signer.core.routes.eth2.HighWatermarkRoute;
import tech.pegasys.web3signer.core.routes.eth2.KeyManagerApiRoute;
import tech.pegasys.web3signer.core.util.ExecutorShutdownUtil;
import tech.pegasys.web3signer.keystorage.aws.AwsSecretsManagerProvider;
import tech.pegasys.web3signer.keystorage.azure.AzureKeyVault;
import tech.pegasys.web3signer.keystorage.common.MappedResults;
import tech.pegasys.web3signer.keystorage.hashicorp.HashicorpConnectionFactory;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.ArtifactSignerProvider;
import tech.pegasys.web3signer.signing.BlsArtifactSigner;
import tech.pegasys.web3signer.signing.bulkloading.BlsAwsBulkLoader;
import tech.pegasys.web3signer.signing.bulkloading.BlsGcpBulkLoader;
import tech.pegasys.web3signer.signing.bulkloading.BlsKeystoreBulkLoader;
import tech.pegasys.web3signer.signing.config.AwsVaultParameters;
import tech.pegasys.web3signer.signing.config.AzureKeyVaultFactory;
import tech.pegasys.web3signer.signing.config.AzureKeyVaultParameters;
import tech.pegasys.web3signer.signing.config.DefaultArtifactSignerProvider;
import tech.pegasys.web3signer.signing.config.GcpSecretManagerParameters;
import tech.pegasys.web3signer.signing.config.KeystoresParameters;
import tech.pegasys.web3signer.signing.config.SignerLoader;
import tech.pegasys.web3signer.signing.config.SignerSourceResult;
import tech.pegasys.web3signer.signing.config.metadata.AbstractArtifactSignerFactory;
import tech.pegasys.web3signer.signing.config.metadata.BlsArtifactSignerFactory;
import tech.pegasys.web3signer.signing.config.metadata.SignerOrigin;
import tech.pegasys.web3signer.signing.config.metadata.parser.SignerParser;
import tech.pegasys.web3signer.signing.config.metadata.parser.YamlMapperFactory;
import tech.pegasys.web3signer.signing.config.metadata.parser.YamlSignerParser;
import tech.pegasys.web3signer.slashingprotection.DbHealthCheck;
import tech.pegasys.web3signer.slashingprotection.DbPrunerRunner;
import tech.pegasys.web3signer.slashingprotection.PostLoadingValidatorsProcessor;
import tech.pegasys.web3signer.slashingprotection.SlashingProtectionContext;
import tech.pegasys.web3signer.slashingprotection.SlashingProtectionContextFactory;
import tech.pegasys.web3signer.slashingprotection.SlashingProtectionParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.Status;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;

public class Eth2Runner extends Runner {
  private static final Logger LOG = LogManager.getLogger();

  private final Optional<SlashingProtectionContext> slashingProtectionContext;
  private final AzureKeyVaultParameters azureKeyVaultParameters;
  private final AwsVaultParameters awsVaultParameters;
  private final GcpSecretManagerParameters gcpSecretManagerParameters;
  private final SlashingProtectionParameters slashingProtectionParameters;
  private final boolean pruningEnabled;
  private final KeystoresParameters keystoresParameters;
  private final Spec eth2Spec;
  private final KeyManagerApiConfig keyManagerApiConfig;
  private final boolean signingExtEnabled;
  private final KeystoresParameters commitBoostApiParameters;

  public Eth2Runner(
      final BaseConfig baseConfig,
      final SlashingProtectionParameters slashingProtectionParameters,
      final AzureKeyVaultParameters azureKeyVaultParameters,
      final KeystoresParameters keystoresParameters,
      final AwsVaultParameters awsVaultParameters,
      final GcpSecretManagerParameters gcpSecretManagerParameters,
      final Spec eth2Spec,
      final KeyManagerApiConfig keyManagerApiConfig,
      final boolean signingExtEnabled,
      final KeystoresParameters commitBoostApiParameters) {
    super(baseConfig);
    this.slashingProtectionContext = createSlashingProtection(slashingProtectionParameters);
    this.azureKeyVaultParameters = azureKeyVaultParameters;
    this.slashingProtectionParameters = slashingProtectionParameters;
    this.pruningEnabled = slashingProtectionParameters.isPruningEnabled();
    this.keystoresParameters = keystoresParameters;
    this.eth2Spec = eth2Spec;
    this.keyManagerApiConfig = keyManagerApiConfig;
    this.awsVaultParameters = awsVaultParameters;
    this.gcpSecretManagerParameters = gcpSecretManagerParameters;
    this.signingExtEnabled = signingExtEnabled;
    this.commitBoostApiParameters = commitBoostApiParameters;
  }

  private Optional<SlashingProtectionContext> createSlashingProtection(
      final SlashingProtectionParameters slashingProtectionParameters) {
    if (slashingProtectionParameters.isEnabled()) {
      try {
        return Optional.of(SlashingProtectionContextFactory.create(slashingProtectionParameters));
      } catch (final IllegalStateException e) {
        throw new InitializationException(e.getMessage(), e);
      }
    } else {
      return Optional.empty();
    }
  }

  @Override
  public void populateRouter(final Context context) {
    new PublicKeysListRoute(context, "eth2").register();
    new Eth2SignRoute(context, eth2Spec, slashingProtectionContext).register();
    new ReloadRoute(context).register();
    new HighWatermarkRoute(context, slashingProtectionContext).register();
    if (signingExtEnabled) {
      new Eth2SignExtensionRoute(context).register();
    }
    if (keyManagerApiConfig.isKeyManagerApiEnabled()) {
      new KeyManagerApiRoute(context, baseConfig, keyManagerApiConfig, slashingProtectionContext)
          .register();
    }
    if (commitBoostApiParameters.isEnabled()) {
      new CommitBoostPublicKeysRoute(context).register();
      new CommitBoostGenerateProxyKeyRoute(context, commitBoostApiParameters, eth2Spec).register();
      new CommitBoostRequestSignatureRoute(context, eth2Spec).register();
    }
  }

  @Override
  protected List<ArtifactSignerProvider> createArtifactSignerProvider(
      final MetricsSystem metricsSystem) {
    // create factory instance ONCE at startup
    final SignerLoader signerLoader = new SignerLoader(baseConfig.getSignerLoaderConfig());

    // Register for cleanup ONCE
    registerClose(signerLoader);

    return List.of(
        new DefaultArtifactSignerProvider(
            createArtifactSignerSupplier(signerLoader, metricsSystem),
            slashingProtectionContext.<BiConsumer<Set<String>, Set<String>>>map(
                PostLoadingValidatorsProcessor::new),
            Optional.of(commitBoostApiParameters)));
  }

  private Supplier<List<SignerSourceResult>> createArtifactSignerSupplier(
      final SignerLoader signerLoader, final MetricsSystem metricsSystem) {
    return () -> {
      try (final AzureKeyVaultFactory azureKeyVaultFactory = new AzureKeyVaultFactory()) {
        final List<SignerSourceResult> results = new ArrayList<>();
        // load keys from key config files
        results.add(
            loadSignersFromKeyConfigFiles(signerLoader, azureKeyVaultFactory, metricsSystem));
        // bulkload keys
        results.addAll(bulkLoadSigners(azureKeyVaultFactory));

        return results;
      }
    };
  }

  private SignerSourceResult loadSignersFromKeyConfigFiles(
      final SignerLoader signerLoader,
      final AzureKeyVaultFactory azureKeyVaultFactory,
      final MetricsSystem metricsSystem) {
    try (final HashicorpConnectionFactory hashicorpConnectionFactory =
            new HashicorpConnectionFactory();
        final AwsSecretsManagerProvider awsSecretsManagerProvider =
            new AwsSecretsManagerProvider(awsVaultParameters.getCacheMaximumSize())) {
      final AbstractArtifactSignerFactory artifactSignerFactory =
          new BlsArtifactSignerFactory(
              baseConfig.getKeyConfigPath(),
              metricsSystem,
              hashicorpConnectionFactory,
              awsSecretsManagerProvider,
              (args) -> new BlsArtifactSigner(args.getKeyPair(), args.getOrigin(), args.getPath()),
              azureKeyVaultFactory);

      final SignerParser signerParser =
          new YamlSignerParser(
              List.of(artifactSignerFactory),
              YamlMapperFactory.createYamlMapper(baseConfig.getKeyStoreConfigFileMaxSize()));
      final SignerSourceResult results = signerLoader.load(signerParser);

      registerSignerLoadingHealthCheck(KEYS_CHECK_CONFIG_FILE_LOADING, results);

      return results;
    }
  }

  private List<SignerSourceResult> bulkLoadSigners(
      final AzureKeyVaultFactory azureKeyVaultFactory) {
    final List<SignerSourceResult> results = new ArrayList<>();
    if (azureKeyVaultParameters.isAzureKeyVaultEnabled()) {
      LOG.info("Bulk loading keys from Azure key vault ... ");
      /*
       Note: Azure supports 25K bytes per secret. https://learn.microsoft.com/en-us/azure/key-vault/secrets/about-secrets
       Each raw bls private key in hex format is approximately 100 bytes. We should store about 200 or fewer
       `\n` delimited keys per secret.
      */
      final SignerSourceResult azureResult =
          SignerSourceResult.fromVault("Azure key vault", loadAzureSigners(azureKeyVaultFactory));
      LOG.info(
          "Keys loaded from Azure: [{}], with error count: [{}]",
          azureResult.signers().size(),
          azureResult.errorCount());
      registerSignerLoadingHealthCheck(KEYS_CHECK_AZURE_BULK_LOADING, azureResult);
      results.add(azureResult);
    }

    if (keystoresParameters.isEnabled()) {
      LOG.info("Bulk loading keys from local keystores ... ");
      final SignerSourceResult keystoreSignersResult =
          keystoresParameters.hasKeystoresPasswordsPath()
              ? BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(
                  keystoresParameters.getKeystoresPath(),
                  keystoresParameters.getKeystoresPasswordsPath())
              : BlsKeystoreBulkLoader.loadKeystoresUsingPasswordFile(
                  keystoresParameters.getKeystoresPath(),
                  keystoresParameters.getKeystoresPasswordFile());
      LOG.info(
          "Keys loaded from local keystores: [{}], with error count: [{}]",
          keystoreSignersResult.signers().size(),
          keystoreSignersResult.errorCount());

      registerSignerLoadingHealthCheck(KEYS_CHECK_KEYSTORE_BULK_LOADING, keystoreSignersResult);
      results.add(keystoreSignersResult);
    }

    if (awsVaultParameters.isEnabled()) {
      LOG.info("Bulk loading keys from AWS Secrets Manager ... ");
      final BlsAwsBulkLoader blsAwsBulkLoader = new BlsAwsBulkLoader();

      final SignerSourceResult awsResult =
          SignerSourceResult.fromVault(
              "AWS Secrets Manager", blsAwsBulkLoader.load(awsVaultParameters));
      LOG.info(
          "Keys loaded from AWS Secrets Manager: [{}], with error count: [{}]",
          awsResult.signers().size(),
          awsResult.errorCount());
      registerSignerLoadingHealthCheck(KEYS_CHECK_AWS_BULK_LOADING, awsResult);
      results.add(awsResult);
    }

    if (gcpSecretManagerParameters.isEnabled()) {
      LOG.info("Bulk loading keys from GCP Secret Manager ... ");
      final BlsGcpBulkLoader blsGcpBulkLoader = new BlsGcpBulkLoader();
      final SignerSourceResult gcpResult =
          SignerSourceResult.fromVault(
              "GCP Secret Manager", blsGcpBulkLoader.load(gcpSecretManagerParameters));
      LOG.info(
          "Keys loaded from GCP Secret Manager: [{}], with error count: [{}]",
          gcpResult.signers().size(),
          gcpResult.errorCount());
      registerSignerLoadingHealthCheck(KEYS_CHECK_GCP_BULK_LOADING, gcpResult);
      results.add(gcpResult);
    }

    return results;
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

  @Override
  public void run() {
    super.run();
    scheduleAndExecuteInitialDbPruning();
    slashingProtectionContext.ifPresent(this::scheduleDbHealthCheck);
  }

  private void scheduleDbHealthCheck(final SlashingProtectionContext protectionContext) {
    final DbHealthCheck dbHealthCheck =
        new DbHealthCheck(
            protectionContext, slashingProtectionParameters.getDbHealthCheckTimeoutMilliseconds());
    final ScheduledExecutorService dbHealthCheckExecutor =
        createSingleThreadScheduledExecutor("db-health-check");

    dbHealthCheckExecutor.scheduleAtFixedRate(
        dbHealthCheck,
        0,
        slashingProtectionParameters.getDbHealthCheckIntervalMilliseconds(),
        TimeUnit.MILLISECONDS);

    super.registerHealthCheckProcedure(
        SLASHING_PROTECTION_DB,
        promise -> promise.complete(dbHealthCheck.isDbUp() ? Status.OK() : Status.KO()));
  }

  private ScheduledExecutorService createSingleThreadScheduledExecutor(final String threadName) {
    final ScheduledExecutorService executorService =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              final Thread thread = new Thread(r, threadName);
              thread.setDaemon(true);
              return thread;
            });

    // Register for cleanup on shutdown
    super.registerClose(
        () -> ExecutorShutdownUtil.shutdownGracefully(executorService, 5, TimeUnit.SECONDS));
    return executorService;
  }

  private void scheduleAndExecuteInitialDbPruning() {
    if (!pruningEnabled || slashingProtectionContext.isEmpty()) {
      return;
    }

    final ScheduledExecutorService prunerExecutor =
        createSingleThreadScheduledExecutor("db-pruner");

    final DbPrunerRunner dbPrunerRunner =
        new DbPrunerRunner(
            slashingProtectionParameters,
            slashingProtectionContext.get().getPruner(),
            prunerExecutor);
    if (slashingProtectionParameters.isPruningAtBootEnabled()) {
      dbPrunerRunner.execute();
    }
    dbPrunerRunner.schedule();
  }

  final MappedResults<ArtifactSigner> loadAzureSigners(
      final AzureKeyVaultFactory azureKeyVaultFactory) {
    final AzureKeyVault keyVault =
        azureKeyVaultFactory.createAzureKeyVault(azureKeyVaultParameters);

    return keyVault.mapSecrets(
        (name, value) -> {
          try {
            final Bytes privateKeyBytes = Bytes.fromHexString(value);
            final BLSKeyPair keyPair =
                new BLSKeyPair(BLSSecretKey.fromBytes(Bytes32.wrap(privateKeyBytes)));
            return new BlsArtifactSigner(keyPair, SignerOrigin.AZURE);
          } catch (final Exception e) {
            LOG.warn("Failed to map to BLS KeyPair from Azure key vault");
            return null;
          }
        },
        azureKeyVaultParameters.getTags());
  }
}
