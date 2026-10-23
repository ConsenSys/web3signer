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
package tech.pegasys.web3signer.signing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.web3signer.BLSTestUtil;
import tech.pegasys.web3signer.FileHiddenUtil;
import tech.pegasys.web3signer.common.Web3SignerMetricCategory;
import tech.pegasys.web3signer.common.config.SignerLoaderConfig;
import tech.pegasys.web3signer.keystorage.aws.AwsSecretsManagerProvider;
import tech.pegasys.web3signer.keystorage.hashicorp.HashicorpConnectionFactory;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.BlsArtifactSigner;
import tech.pegasys.web3signer.signing.config.metadata.BlsArtifactSignerFactory;
import tech.pegasys.web3signer.signing.config.metadata.SigningMetadataException;
import tech.pegasys.web3signer.signing.config.metadata.parser.SignerParser;
import tech.pegasys.web3signer.signing.config.metadata.parser.YamlMapperFactory;
import tech.pegasys.web3signer.signing.config.metadata.parser.YamlSignerParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.io.FileUtils;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignerLoaderTest {
  private static final YAMLMapper YAML_MAPPER = YamlMapperFactory.createYamlMapper();
  @TempDir Path configsDirectory;
  @Mock private MetricsSystem metricsSystem;
  @Mock private HashicorpConnectionFactory hashicorpConnectionFactory;
  @Mock private AwsSecretsManagerProvider awsSecretsManagerProvider;
  @Mock private AzureKeyVaultFactory azureKeyVaultFactory;
  @Mock private LabelledMetric<OperationTimer> privateKeyRetrievalTimer;
  @Mock private OperationTimer operationTimer;

  private static final BLSKeyPair blsKeyPair1 = BLSTestUtil.randomKeyPair(1);
  private static final BLSKeyPair blsKeyPair2 = BLSTestUtil.randomKeyPair(2);
  private static final BLSKeyPair blsKeyPair3 = BLSTestUtil.randomKeyPair(3);

  private SignerParser signerParser;
  private SignerLoader signerLoader;

  @BeforeEach
  public void setup() {
    // setup metrics system stubbing
    lenient()
        .when(
            metricsSystem.createLabelledTimer(
                Web3SignerMetricCategory.SIGNING,
                "private_key_retrieval_time",
                "Time taken to retrieve private key",
                "signer"))
        .thenReturn(privateKeyRetrievalTimer);

    lenient().when(privateKeyRetrievalTimer.labels(any())).thenReturn(operationTimer);

    final BlsArtifactSignerFactory blsArtifactSignerFactory =
        new BlsArtifactSignerFactory(
            configsDirectory,
            metricsSystem,
            hashicorpConnectionFactory,
            awsSecretsManagerProvider,
            (args) -> new BlsArtifactSigner(args.getKeyPair(), args.getOrigin(), args.getPath()),
            azureKeyVaultFactory);

    signerParser =
        new YamlSignerParser(
            List.of(blsArtifactSignerFactory), YamlMapperFactory.createYamlMapper());

    signerLoader = new SignerLoader(SignerLoaderConfig.withDefaults(configsDirectory));
  }

  @AfterEach
  void cleanup() throws Exception {
    if (signerLoader != null) {
      signerLoader.close();
    }
  }

  @ParameterizedTest(name = "{index} - Signer created for file name {0}")
  @MethodSource("validMetadataFileNameProvider")
  void signerReturnedForValidMetadataFile(final String fileName) throws IOException {
    final String privateKeyHex = blsKeyPair1.getSecretKey().toBytes().toHexString();
    createFileInConfigsDirectory(fileName, privateKeyHex);

    signerLoader.load(signerParser);
    final Collection<ArtifactSigner> signerList = signerLoader.load(signerParser).artifactSigners();

    assertThat(signerList.size()).isOne();
    assertThat(signerList.stream().findFirst().orElseThrow().getIdentifier())
        .isEqualTo(blsKeyPair1.getPublicKey().toHexString());
  }

  static Stream<String> validMetadataFileNameProvider() {
    return Stream.of(
        configFileName(blsKeyPair1),
        "prefix_" + configFileName(blsKeyPair1),
        "test.yaml",
        "test.YAML",
        "test.yml",
        "test.YML");
  }

  @Test
  void wrongFileExtensionReturnsEmptySigner() throws IOException {
    final String filename = blsKeyPair1.getPublicKey().toHexString() + ".nothing";
    final String privateKeyHex = blsKeyPair1.getSecretKey().toBytes().toHexString();
    createFileInConfigsDirectory(filename, privateKeyHex);

    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).isEmpty();
    assertThat(result.errorCount()).isZero();
  }

  @Test
  void failedParserReturnsEmptySigner() throws IOException {
    createFileInConfigsDirectory(configFileName(blsKeyPair1), "NOT_A_VALID_KEY");

    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).isEmpty();
    assertThat(result.errorCount()).isOne();
  }

  @Test
  void failedWithDirectoryErrorReturnsSourceFailure() throws IOException {

    final Path missingConfigDir = configsDirectory.resolve("idontexist");
    try (SignerLoader signerLoaderWithInvalidConfigDir =
        new SignerLoader(SignerLoaderConfig.withDefaults(missingConfigDir))) {
      final SignerSourceResult result = signerLoaderWithInvalidConfigDir.load(signerParser);

      assertThat(result.artifactSigners()).isEmpty();
      assertThat(result.errorCount()).isOne();
      assertThat(result.sourceFailed()).isTrue();
    }
  }

  @Test
  void multipleMatchesForSameIdentifierReturnsSameSigners() throws IOException {
    final String filename1 = "1_" + configFileName(blsKeyPair1);
    final String filename2 = "2_" + configFileName(blsKeyPair1);
    final String privateKeyHex = blsKeyPair1.getSecretKey().toBytes().toHexString();
    createFileInConfigsDirectory(filename1, privateKeyHex);
    createFileInConfigsDirectory(filename2, privateKeyHex);

    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(1);
    assertThat(result.errorCount()).isZero();
  }

  @Test
  void signerIdentifiersNotReturnedInvalidMetadataFile() throws IOException {
    createEmptyFileInConfigsDirectory(configFileName(blsKeyPair1));
    createEmptyFileInConfigsDirectory(configFileName(blsKeyPair2));
    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).isEmpty();
    assertThat(result.errorCount()).isEqualTo(2);
  }

  @Test
  void signerIdentifiersNotReturnedForHiddenFiles() throws IOException {
    final String privateKeyHex1 = blsKeyPair1.getSecretKey().toBytes().toHexString();
    final String privateKeyHex2 = blsKeyPair2.getSecretKey().toBytes().toHexString();
    final Path hiddenFile =
        createFileInConfigsDirectory(configFileName(blsKeyPair1), privateKeyHex1);
    FileHiddenUtil.makeFileHidden(hiddenFile);
    createFileInConfigsDirectory(configFileName(blsKeyPair2), privateKeyHex2);

    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(1);
    assertThat(result.artifactSigners().stream().findFirst().orElseThrow().getIdentifier())
        .isEqualTo(blsKeyPair2.getPublicKey().toHexString());
    assertThat(result.errorCount()).isZero();
  }

  @Test
  void signerIdentifiersReturnedForAllValidMetadataFilesInDirectory() throws IOException {
    final String privateKeyHex1 = blsKeyPair1.getSecretKey().toBytes().toHexString();
    final String privateKeyHex2 = blsKeyPair2.getSecretKey().toBytes().toHexString();
    final String privateKeyHex3 = blsKeyPair3.getSecretKey().toBytes().toHexString();

    createFileInConfigsDirectory(configFileName(blsKeyPair1), privateKeyHex1);
    createFileInConfigsDirectory(configFileName(blsKeyPair2), privateKeyHex2);
    createFileInConfigsDirectory(configFileName(blsKeyPair3), privateKeyHex3);

    SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(3);
    assertThat(
            result.artifactSigners().stream()
                .map(ArtifactSigner::getIdentifier)
                .collect(Collectors.toList()))
        .containsOnly(
            blsKeyPair1.getPublicKey().toHexString(),
            blsKeyPair2.getPublicKey().toHexString(),
            blsKeyPair3.getPublicKey().toHexString());
  }

  @Test
  void callingLoadTwiceReturnedSameNumberOfArtifacts() throws IOException {
    final String privateKeyHex1 = blsKeyPair1.getSecretKey().toBytes().toHexString();
    final String privateKeyHex2 = blsKeyPair2.getSecretKey().toBytes().toHexString();
    final String privateKeyHex3 = blsKeyPair3.getSecretKey().toBytes().toHexString();

    createFileInConfigsDirectory(configFileName(blsKeyPair1), privateKeyHex1);
    createFileInConfigsDirectory(configFileName(blsKeyPair2), privateKeyHex2);
    createFileInConfigsDirectory(configFileName(blsKeyPair3), privateKeyHex3);

    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(3);
    assertThat(
            result.artifactSigners().stream()
                .map(ArtifactSigner::getIdentifier)
                .collect(Collectors.toList()))
        .containsOnly(
            blsKeyPair1.getPublicKey().toHexString(),
            blsKeyPair2.getPublicKey().toHexString(),
            blsKeyPair3.getPublicKey().toHexString());

    final SignerSourceResult reloadedResult = signerLoader.load(signerParser);
    assertThat(reloadedResult.artifactSigners()).hasSize(3);
  }

  @Test
  void callingLoadTwiceWithRemovedFiles() throws IOException {
    final String privateKeyHex1 = blsKeyPair1.getSecretKey().toBytes().toHexString();
    final String privateKeyHex2 = blsKeyPair2.getSecretKey().toBytes().toHexString();
    final String privateKeyHex3 = blsKeyPair3.getSecretKey().toBytes().toHexString();

    createFileInConfigsDirectory(configFileName(blsKeyPair1), privateKeyHex1);
    createFileInConfigsDirectory(configFileName(blsKeyPair2), privateKeyHex2);
    createFileInConfigsDirectory(configFileName(blsKeyPair3), privateKeyHex3);

    final SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(3);
    assertThat(
            result.artifactSigners().stream()
                .map(ArtifactSigner::getIdentifier)
                .collect(Collectors.toList()))
        .containsOnly(
            blsKeyPair1.getPublicKey().toHexString(),
            blsKeyPair2.getPublicKey().toHexString(),
            blsKeyPair3.getPublicKey().toHexString());

    // remove file
    Files.delete(configsDirectory.resolve(configFileName(blsKeyPair3)));

    final Collection<ArtifactSigner> reloadedArtifactSigner =
        signerLoader.load(signerParser).artifactSigners();
    assertThat(reloadedArtifactSigner).hasSize(2);
    assertThat(
            reloadedArtifactSigner.stream()
                .map(ArtifactSigner::getIdentifier)
                .collect(Collectors.toList()))
        .containsOnly(
            blsKeyPair1.getPublicKey().toHexString(), blsKeyPair2.getPublicKey().toHexString());
  }

  @Test
  void calculateTimeTakenWithFutureTimeShouldReturnEmpty() {
    final Optional<String> timeTaken =
        SignerLoader.calculateTimeTaken(Instant.now().plus(5, ChronoUnit.MINUTES));
    assertThat(timeTaken).isEmpty();
  }

  @Test
  void calculateTimeTakenWithPastTimeShouldReturnValue() {
    final Optional<String> timeTaken =
        SignerLoader.calculateTimeTaken(Instant.now().minus(5, ChronoUnit.MINUTES));
    assertThat(timeTaken).isNotEmpty();
  }

  @Test
  void loadLargeNumberOfSigners() throws Exception {
    for (int i = 0; i < 15000; i++) {
      BLSKeyPair blsKey = BLSTestUtil.randomKeyPair(i);
      createFileInConfigsDirectory(
          configFileName(blsKey), blsKey.getSecretKey().toBytes().toHexString());
    }

    SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(15000);
    assertThat(result.errorCount()).isZero();

    // loading again will return cached results - verify no new files processed
    SignerLoader spyLoader = spy(signerLoader);
    result = spyLoader.load(signerParser);
    assertThat(result.artifactSigners()).hasSize(15000);
    assertThat(result.errorCount()).isZero();

    // Verify processFile was never called (all cached)
    verify(spyLoader, never()).processFile(any(), any(), any(), anyInt());

    // add 6005 more files
    for (int i = 15000; i < 21005; i++) {
      BLSKeyPair blsKey = BLSTestUtil.randomKeyPair(i);
      createFileInConfigsDirectory(
          configFileName(blsKey), blsKey.getSecretKey().toBytes().toHexString());
    }

    // load again. Total should assert to 21005
    result = signerLoader.load(signerParser);
    assertThat(result.artifactSigners()).hasSize(21005);
    assertThat(result.errorCount()).isZero();
  }

  @Test
  void modifiedTimeStampConfigFileReloads() throws Exception {
    final List<BLSKeyPair> blsKeys = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final BLSKeyPair blsKey = BLSTestUtil.randomKeyPair(i);
      createFileInConfigsDirectory(
          configFileName(blsKey), blsKey.getSecretKey().toBytes().toHexString());
      blsKeys.add(blsKey);
    }

    SignerSourceResult result = signerLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(5);
    assertThat(result.errorCount()).isZero();

    // rewrite 3 files again, should result in new timestamp, hence reloaded
    Thread.sleep(10); // Ensure different timestamp
    for (int i = 1; i < 4; i++) {
      final BLSKeyPair blsKey = blsKeys.get(i);
      createFileInConfigsDirectory(
          configFileName(blsKey), blsKey.getSecretKey().toBytes().toHexString());
    }

    // Create spy to verify reprocessing
    SignerLoader spyLoader = spy(signerLoader);
    result = spyLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(5);
    assertThat(result.errorCount()).isZero();

    // Verify processFile called for the 3 modified files
    verify(spyLoader, times(3)).processFile(any(), eq(signerParser), any(), eq(3));
  }

  @Test
  void modifiedFileThatFailsToParseIsReportedAndRetried() throws Exception {
    final Path path1 =
        createFileInConfigsDirectory(
            configFileName(blsKeyPair1), blsKeyPair1.getSecretKey().toBytes().toHexString());
    createFileInConfigsDirectory(
        configFileName(blsKeyPair2), blsKeyPair2.getSecretKey().toBytes().toHexString());

    SignerSourceResult result = signerLoader.load(signerParser);
    assertThat(result.artifactSigners()).hasSize(2);
    assertThat(result.errorCount()).isZero();
    assertThat(result.failedFiles()).isEmpty();

    // corrupt file 1 and bump its modified time
    final FileTime originalTime = Files.getLastModifiedTime(path1);
    Files.writeString(path1, "NOT_A_VALID_KEY");
    Files.setLastModifiedTime(path1, FileTime.fromMillis(originalTime.toMillis() + 1000));

    result = signerLoader.load(signerParser);
    // the failing file is named, so that the provider keeps the signer it previously loaded from
    // it; the other signers are unaffected
    assertThat(result.failedFiles()).containsExactly(path1.toString());
    assertThat(identifiers(result)).containsExactly(blsKeyPair2.getPublicKey().toHexString());
    assertThat(result.errorCount()).isOne();

    // the failing file is processed again on the next load
    final SignerLoader spyLoader = spy(signerLoader);
    result = spyLoader.load(signerParser);
    verify(spyLoader, times(1)).processFile(any(), eq(signerParser), any(), eq(1));
    assertThat(result.failedFiles()).containsExactly(path1.toString());
    assertThat(result.errorCount()).isOne();

    // fixing the file clears the error
    createFileInConfigsDirectory(
        configFileName(blsKeyPair1), blsKeyPair1.getSecretKey().toBytes().toHexString());
    Files.setLastModifiedTime(path1, FileTime.fromMillis(originalTime.toMillis() + 2000));
    result = spyLoader.load(signerParser);
    assertThat(result.artifactSigners()).hasSize(2);
    assertThat(result.errorCount()).isZero();

    // a deleted file is removed
    Files.delete(path1);
    result = spyLoader.load(signerParser);
    assertThat(identifiers(result)).containsExactly(blsKeyPair2.getPublicKey().toHexString());
    assertThat(result.errorCount()).isZero();
  }

  @Test
  void inaccessibleConfigDirectoryReportsSourceFailure() throws Exception {
    final Path subDirectory = Files.createDirectory(configsDirectory.resolve("sub"));
    createFileInDirectory(
        subDirectory,
        configFileName(blsKeyPair1),
        blsKeyPair1.getSecretKey().toBytes().toHexString());
    createFileInDirectory(
        subDirectory,
        configFileName(blsKeyPair2),
        blsKeyPair2.getSecretKey().toBytes().toHexString());

    try (SignerLoader subDirectoryLoader =
        new SignerLoader(SignerLoaderConfig.withDefaults(subDirectory))) {
      assertThat(subDirectoryLoader.load(signerParser).artifactSigners()).hasSize(2);

      FileUtils.deleteDirectory(subDirectory.toFile());

      final SignerSourceResult result = subDirectoryLoader.load(signerParser);
      assertThat(result.artifactSigners()).isEmpty();
      assertThat(result.errorCount()).isOne();
      assertThat(result.sourceFailed()).isTrue();
    }
  }

  // ==================== TIMEOUT BEHAVIOR TESTS ====================

  @Test
  @Timeout(30)
  void taskTimeoutCancelsLongRunningTask() throws Exception {
    // Create a slow parser that takes longer than the timeout
    SignerParser slowParser = createSlowParser(10000); // 10 second delay

    createBLSRawConfigFiles(3);

    // Create loader with 2 second timeout
    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, 500, 2, 1))) {
      SignerSourceResult result = testLoader.load(slowParser);

      // All tasks should timeout and be cancelled
      assertThat(result.errorCount()).isEqualTo(3);
      assertThat(result.artifactSigners()).isEmpty();
    }
  }

  @Test
  @Timeout(15)
  void customTimeoutIsRespected() throws Exception {
    SignerParser slowParser = createSlowParser(500); // 500ms delay

    createBLSRawConfigFiles(2);

    // Create loader with 1 second timeout (should succeed with 500ms tasks)
    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, 500, 1, 1))) {
      SignerSourceResult result = testLoader.load(slowParser);

      // Tasks should complete within timeout
      assertThat(result.errorCount()).isZero();
      assertThat(result.artifactSigners()).hasSize(2);
    }
  }

  @Test
  void minimumTimeoutIsEnforced() {
    // taskTimeoutSeconds with value less than 1 should be clamped to 1
    SignerLoaderConfig signerLoaderConfig =
        new SignerLoaderConfig(configsDirectory, true, 500, 0, 0);
    assertThat(signerLoaderConfig.taskTimeoutSeconds()).isOne();
    assertThat(signerLoaderConfig.sequentialThreshold()).isOne();
  }

  // ==================== BATCH SIZE BEHAVIOR TESTS ====================

  @Test
  void batchSizeControlsProgressLogging() throws Exception {
    int fileCount = 250;
    int batchSize = 120;
    createBLSRawConfigFiles(fileCount);
    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, batchSize, 60, 100))) {

      SignerLoader spyLoader = spy(testLoader);
      spyLoader.load(signerParser);

      // Verify processInBatches was called (not processSequentially)
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(fileCount));
      verify(spyLoader, never()).processSequentially(any(), any(), any(), anyInt());

      // With 250 files and batch size 120, should call collectBatchResults 3 times
      // (batches: 1-120, 121-240, 241-250)
      verify(spyLoader, times(3)).collectBatchResults(any(), any());
    }
  }

  @Test
  void largeBatchSizeProcessesInSingleBatch() throws Exception {
    int fileCount = 150;
    int batchSize = 200;

    createBLSRawConfigFiles(fileCount);

    // Use batch size larger than file count
    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, batchSize, 60, 100))) {

      SignerLoader spyLoader = spy(testLoader);
      SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(fileCount);
      assertThat(result.errorCount()).isZero();

      // Should use parallel processing
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(fileCount));

      // Should only call collectBatchResults once (single batch)
      verify(spyLoader, times(1)).collectBatchResults(any(), any());
    }
  }

  @Test
  void batchSizeLessThan100IsClampedTo100() throws Exception {
    int fileCount = 150;
    int batchSize = 50; // should be clamped to 100
    createBLSRawConfigFiles(fileCount);
    SignerLoaderConfig config = new SignerLoaderConfig(configsDirectory, true, batchSize, 60, 100);
    assertThat(config.batchSize()).isEqualTo(100);

    try (SignerLoader testLoader = new SignerLoader(config)) {

      SignerLoader spyLoader = spy(testLoader);
      spyLoader.load(signerParser);

      // Verify it used batch processing
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(fileCount));
    }
  }

  @Test
  void exactlyOneBatchWhenFilesEqualBatchSize() throws Exception {
    int fileCount = 100;
    int batchSize = 100;
    createBLSRawConfigFiles(fileCount);
    SignerLoaderConfig config = new SignerLoaderConfig(configsDirectory, true, batchSize, 60, 100);
    try (SignerLoader testLoader = new SignerLoader(config)) {

      SignerLoader spyLoader = spy(testLoader);
      spyLoader.load(signerParser);

      // Should use parallel processing
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(fileCount));

      // Should call collectBatchResults exactly once (single batch)
      verify(spyLoader, times(1)).collectBatchResults(any(), any());
    }
  }

  @Test
  void batchProgressOnlyLoggedWhenTotalExceedsBatchSize() throws Exception {
    int fileCount = 150;
    int batchSize = 100;
    createBLSRawConfigFiles(fileCount);

    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, batchSize, 60, 100))) {

      SignerLoader spyLoader = spy(testLoader);
      spyLoader.load(signerParser);

      // With 150 files and batch size 100, should see 2 batches
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(fileCount));
      verify(spyLoader, times(2)).collectBatchResults(any(), any());
    }
  }

  // ==================== SEQUENTIAL VS PARALLEL PROCESSING TESTS ====================

  @Test
  void sequentialProcessingWhenBelowThreshold() throws Exception {
    // Create files below sequential threshold (default 100)
    createBLSRawConfigFiles(50);

    SignerLoader spyLoader = spy(signerLoader);
    SignerSourceResult result = spyLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(50);
    assertThat(result.errorCount()).isZero();

    // Verify sequential processing was used
    verify(spyLoader, times(1)).processSequentially(any(), eq(signerParser), any(), eq(50));
    verify(spyLoader, never()).processInBatches(any(), any(), any(), anyInt());
  }

  @Test
  void parallelProcessingWhenAboveThreshold() throws Exception {
    // Create files above sequential threshold
    createBLSRawConfigFiles(150);

    SignerLoader spyLoader = spy(signerLoader);
    final SignerSourceResult result = spyLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(150);
    assertThat(result.errorCount()).isZero();

    // Verify parallel processing was used
    verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(150));
    verify(spyLoader, never()).processSequentially(any(), any(), any(), anyInt());
  }

  @Test
  void sequentialProcessingWhenParallelDisabled() throws Exception {
    // Create many files
    createBLSRawConfigFiles(200);

    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, false, 1, 60, 1))) {
      SignerLoader spyLoader = spy(testLoader);
      final SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(200);
      assertThat(result.errorCount()).isZero();

      // Verify sequential processing was used despite file count
      verify(spyLoader, times(1)).processSequentially(any(), eq(signerParser), any(), eq(200));
      verify(spyLoader, never()).processInBatches(any(), any(), any(), anyInt());
    }
  }

  @Test
  void customSequentialThresholdIsRespected() throws Exception {
    createBLSRawConfigFiles(75);

    // Set custom threshold to 50
    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, 500, 60, 50))) {

      SignerLoader spyLoader = spy(testLoader);
      final SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(75);

      // With 75 files and threshold of 50, should use parallel processing
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(75));
      verify(spyLoader, never()).processSequentially(any(), any(), any(), anyInt());
    }
  }

  @Test
  void minimumSequentialThresholdIsEnforced() {
    // sequentialThreshold less than 1 should be clamped to 1
    SignerLoaderConfig config = new SignerLoaderConfig(configsDirectory, true, 100, 60, 0);
    assertThat(config.sequentialThreshold()).isOne();
  }

  @Test
  void sequentialProcessingProducesCorrectResults() throws Exception {
    final List<BLSKeyPair> keyPairs = createBLSRawConfigFiles(10);

    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, false, 100, 60, 100))) {
      SignerLoader spyLoader = spy(testLoader);
      final SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(10);

      // Verify all expected signers are present
      final List<String> expectedIds =
          keyPairs.stream().map(kp -> kp.getPublicKey().toHexString()).collect(Collectors.toList());

      final List<String> actualIds =
          result.artifactSigners().stream()
              .map(ArtifactSigner::getIdentifier)
              .collect(Collectors.toList());

      assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedIds);

      // Verify sequential processing was used
      verify(spyLoader, times(1)).processSequentially(any(), eq(signerParser), any(), eq(10));
    }
  }

  @Test
  void emptyDirectoryReturnsNoSigners() {
    // Don't create any files
    SignerLoader spyLoader = spy(signerLoader);
    SignerSourceResult result = spyLoader.load(signerParser);

    assertThat(result.artifactSigners()).isEmpty();
    assertThat(result.errorCount()).isZero();

    // Verify neither processing method was called
    verify(spyLoader, never()).processSequentially(any(), any(), any(), anyInt());
    verify(spyLoader, never()).processInBatches(any(), any(), any(), anyInt());
  }

  @Test
  void atThresholdUsesParallelProcessing() throws Exception {
    createBLSRawConfigFiles(100);

    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, 500, 60, 100))) {

      SignerLoader spyLoader = spy(testLoader);
      SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(100);

      // At threshold boundary (not below), should use parallel
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(100));
      verify(spyLoader, never()).processSequentially(any(), any(), any(), anyInt());
    }
  }

  @Test
  void oneBelowThresholdUsesSequentialProcessing() throws Exception {
    createBLSRawConfigFiles(99);

    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, 500, 60, 100))) {
      SignerLoader spyLoader = spy(testLoader);
      SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(99);

      // Below threshold (99 < 100), should use sequential
      verify(spyLoader, times(1)).processSequentially(any(), eq(signerParser), any(), eq(99));
      verify(spyLoader, never()).processInBatches(any(), any(), any(), anyInt());
    }
  }

  @Test
  void oneFileAboveThresholdUsesParallelProcessing() throws Exception {
    createBLSRawConfigFiles(101);

    try (SignerLoader testLoader =
        new SignerLoader(new SignerLoaderConfig(configsDirectory, true, 500, 60, 100))) {

      SignerLoader spyLoader = spy(testLoader);
      SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(101);

      // Just above threshold should use parallel
      verify(spyLoader, times(1)).processInBatches(any(), eq(signerParser), any(), eq(101));
      verify(spyLoader, never()).processSequentially(any(), any(), any(), anyInt());
    }
  }

  @Test
  void closedLoaderThrowsExceptionOnLoad() throws Exception {
    createBLSRawConfigFiles(5);
    signerLoader.close();

    assertThatThrownBy(() -> signerLoader.load(signerParser))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SignerLoader instance has been closed");
  }

  @Test
  void filesNotProcessedBeforeLoaderIsClosedAreReportedAsFailed() throws Exception {
    final Set<String> files =
        Set.of(
            configsDirectory.resolve("1.yaml").toString(),
            configsDirectory.resolve("2.yaml").toString());
    signerLoader.close();

    final SignerLoader.LoadResult result =
        signerLoader.processInBatches(files, signerParser, new AtomicLong(), files.size());

    // the files were not deleted, so their previously loaded signers must be kept
    assertThat(result.loadedSigners()).isEmpty();
    assertThat(result.failedPaths()).containsExactlyInAnyOrderElementsOf(files);
  }

  @Test
  void interruptedFileIsReportedAsFailed() throws Exception {
    final Path file =
        createFileInConfigsDirectory(
            configFileName(blsKeyPair1), blsKeyPair1.getSecretKey().toBytes().toHexString());

    final SignerLoader.LoadResult result;
    Thread.currentThread().interrupt();
    try {
      result = signerLoader.processFile(file.toString(), signerParser, new AtomicLong(), 1);
    } finally {
      Thread.interrupted();
    }

    assertThat(result.loadedSigners()).isEmpty();
    assertThat(result.failedPaths()).containsExactly(file.toString());
  }

  @Test
  void multipleClosesAreIdempotent() throws Exception {
    createBLSRawConfigFiles(5);

    // Load once to initialize
    signerLoader.load(signerParser);

    // Close multiple times should not throw
    signerLoader.close();
    signerLoader.close();
    signerLoader.close();

    // Subsequent load should fail
    assertThatThrownBy(() -> signerLoader.load(signerParser))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cachingPreventsDuplicateProcessing() throws Exception {
    createBLSRawConfigFiles(50);

    // First load
    SignerSourceResult firstResult = signerLoader.load(signerParser);
    assertThat(firstResult.artifactSigners()).hasSize(50);

    // Create spy for second load
    SignerLoader spyLoader = spy(signerLoader);
    SignerSourceResult secondResult = spyLoader.load(signerParser);

    assertThat(secondResult.artifactSigners()).hasSize(50);

    // Verify no files were processed (all cached)
    verify(spyLoader, never()).processFile(any(), any(), any(), anyInt());
  }

  @Test
  void addingNewFilesProcessesOnlyNewFiles() throws Exception {
    // Initial load with 10 files
    createBLSRawConfigFiles(10);
    signerLoader.load(signerParser);

    // Add 5 more files
    for (int i = 10; i < 15; i++) {
      BLSKeyPair blsKey = BLSTestUtil.randomKeyPair(i);
      createFileInConfigsDirectory(
          configFileName(blsKey), blsKey.getSecretKey().toBytes().toHexString());
    }

    // Create spy for reload
    SignerLoader spyLoader = spy(signerLoader);
    SignerSourceResult result = spyLoader.load(signerParser);

    assertThat(result.artifactSigners()).hasSize(15);

    // Verify only 5 new files were processed
    verify(spyLoader, times(5)).processFile(any(), eq(signerParser), any(), eq(5));
  }

  @Test
  void batchBoundaryHandling() throws Exception {
    // Test exact batch boundaries
    int[] testSizes = {99, 100, 101, 199, 200, 201};

    for (int size : testSizes) {
      // Clean up previous test
      if (signerLoader != null) {
        signerLoader.close();
      }

      // Create new directory and files
      Path testDir = Files.createTempDirectory("batch-test-" + size);
      createBLSRawConfigFiles(testDir, size);

      signerLoader = new SignerLoader(new SignerLoaderConfig(testDir, true, 100, 60, 100));

      SignerLoader spyLoader = spy(signerLoader);
      SignerSourceResult result = spyLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(size);
      assertThat(result.errorCount()).isZero();

      // Calculate expected number of batches
      boolean shouldUseParallel = size >= 100;
      if (shouldUseParallel) {
        int expectedBatches = (size + 99) / 100; // Ceiling division
        verify(spyLoader, times(expectedBatches)).collectBatchResults(any(), any());
      }
    }
  }

  @Test
  void configValidationInConstructor() throws IOException {
    // Test that config validation happens in SignerLoaderConfig, not SignerLoader
    SignerLoaderConfig config = new SignerLoaderConfig(configsDirectory, true, -100, -5, -50);

    // Values should be clamped to minimums
    assertThat(config.batchSize()).isEqualTo(100);
    assertThat(config.taskTimeoutSeconds()).isEqualTo(1);
    assertThat(config.sequentialThreshold()).isEqualTo(1);

    // Should be able to create loader with clamped config
    try (SignerLoader loader = new SignerLoader(config)) {
      assertThat(loader).isNotNull();
    }
  }

  @Test
  void progressReportingWithVaryingSizes() throws Exception {
    // Test progress reporting at different file counts
    int[] testCounts = {10, 100, 1000, 5000};

    for (int count : testCounts) {
      if (signerLoader != null) {
        signerLoader.close();
      }

      Path testDir = Files.createTempDirectory("progress-test-" + count);
      createBLSRawConfigFiles(testDir, count);

      signerLoader = new SignerLoader(SignerLoaderConfig.withDefaults(testDir));

      SignerSourceResult result = signerLoader.load(signerParser);

      assertThat(result.artifactSigners()).hasSize(count);
      assertThat(result.errorCount()).isZero();
    }
  }

  private static List<String> identifiers(final SignerSourceResult result) {
    return result.artifactSigners().stream().map(ArtifactSigner::getIdentifier).toList();
  }

  // ==================== HELPER METHODS ====================

  private List<BLSKeyPair> createBLSRawConfigFiles(final int numberOfFiles) throws IOException {
    return createBLSRawConfigFiles(configsDirectory, numberOfFiles);
  }

  private List<BLSKeyPair> createBLSRawConfigFiles(final Path directory, final int numberOfFiles)
      throws IOException {
    final List<BLSKeyPair> keyPairs = new ArrayList<>();
    for (int i = 0; i < numberOfFiles; i++) {
      final BLSKeyPair blsKey = BLSTestUtil.randomKeyPair(i);
      keyPairs.add(blsKey);
      createFileInDirectory(
          directory, configFileName(blsKey), blsKey.getSecretKey().toBytes().toHexString());
    }
    return keyPairs;
  }

  private Path createFileInConfigsDirectory(final String fileName, final String privateKeyHex)
      throws IOException {
    return createFileInDirectory(configsDirectory, fileName, privateKeyHex);
  }

  private Path createFileInDirectory(
      final Path directory, final String fileName, final String privateKeyHex) throws IOException {
    final Path file = directory.resolve(fileName);

    final Map<String, String> unencryptedKeyMetadataFile = new HashMap<>();
    unencryptedKeyMetadataFile.put("type", "file-raw");
    unencryptedKeyMetadataFile.put("privateKey", privateKeyHex);
    final String yamlContent = YAML_MAPPER.writeValueAsString(unencryptedKeyMetadataFile);

    Files.writeString(file, yamlContent);
    assertThat(file).exists();
    return file;
  }

  private static String configFileName(final BLSKeyPair blsKeyPair) {
    return blsKeyPair.getPublicKey().toHexString() + ".yaml";
  }

  private void createEmptyFileInConfigsDirectory(final String filename) throws IOException {
    final File file = configsDirectory.resolve(filename).toFile();
    assertThat(file.createNewFile()).isTrue();
  }

  /** Creates a parser that introduces a delay to simulate slow processing */
  private SignerParser createSlowParser(long delayMillis) throws SigningMetadataException {
    SignerParser slowParser = spy(signerParser);

    doAnswer(
            invocation -> {
              Thread.sleep(delayMillis);
              return invocation.callRealMethod();
            })
        .when(slowParser)
        .parse(any());

    return slowParser;
  }
}
