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
package tech.pegasys.web3signer.signing.bulkloading;

import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.web3signer.BLSTestUtil;
import tech.pegasys.web3signer.KeystoreUtil;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.config.SignerSourceResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlsKeystoreBulkLoaderTest {

  private static final BLSKeyPair KEY_PAIR_1 = BLSTestUtil.randomKeyPair(0);
  private static final BLSKeyPair KEY_PAIR_2 = BLSTestUtil.randomKeyPair(1);
  private static final String KEYSTORE_PASSWORD_1 = "password1";
  private static final String KEYSTORE_PASSWORD_2 = "password2";

  @Test
  void loadingEmptyKeystoreDirReturnsNoSigners(
      final @TempDir Path keystoreDir, final @TempDir Path passwordDir) {
    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    assertThat(result.signers()).isEmpty();
    assertThat(result.errorCount()).isZero();
    assertThat(result.failedFiles()).isEmpty();
    assertThat(result.sourceFailed()).isFalse();
  }

  @Test
  void loadsMultipleKeystores(final @TempDir Path keystoreDir, final @TempDir Path passwordDir) {
    KeystoreUtil.createKeystore(KEY_PAIR_1, keystoreDir, passwordDir, "password1");
    KeystoreUtil.createKeystore(KEY_PAIR_2, keystoreDir, passwordDir, KEYSTORE_PASSWORD_2);
    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    final Collection<ArtifactSigner> signers = result.artifactSigners();

    assertThat(signers).hasSize(2);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_1);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_2);
    assertThat(result.errorCount()).isZero();

    // each signer names the keystore file it was loaded from
    assertThat(result.signers())
        .allSatisfy(
            loaded ->
                assertThat(loaded.file())
                    .contains(
                        keystoreDir.resolve(loaded.signer().getIdentifier() + ".json").toString()));
  }

  @Test
  void loadsMultipleKeystoresUsingSinglePasswordFile(final @TempDir Path tempDir)
      throws IOException {
    final Path keystoreDir = tempDir.resolve("keystores");
    Files.createDirectory(keystoreDir);
    KeystoreUtil.createKeystoreFile(KEY_PAIR_1, keystoreDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystoreFile(KEY_PAIR_2, keystoreDir, KEYSTORE_PASSWORD_1);
    final Path passwordFile = tempDir.resolve("password.txt");
    Files.writeString(passwordFile, KEYSTORE_PASSWORD_1);

    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordFile(keystoreDir, passwordFile);
    final Collection<ArtifactSigner> signers = result.artifactSigners();
    assertThat(signers).hasSize(2);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_1);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_2);

    assertThat(result.errorCount()).isZero();
  }

  @Test
  void ignoresNonJsonFiles(final @TempDir Path keystoreDir, final @TempDir Path passwordDir)
      throws IOException {
    KeystoreUtil.createKeystore(KEY_PAIR_1, keystoreDir, passwordDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystore(KEY_PAIR_2, keystoreDir, passwordDir, KEYSTORE_PASSWORD_2);

    // rename keypair 0 so it now ignored
    final Path sourcePath = keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".json");
    final Path targetPath = keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".ignored");
    Files.move(sourcePath, targetPath);

    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    final Collection<ArtifactSigner> signers = result.artifactSigners();
    assertThat(signers).hasSize(1);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_2);

    assertThat(result.errorCount()).isZero();
    assertThat(result.failedFiles()).isEmpty();
  }

  @Test
  void keystoreWithoutPasswordCausesErrorCountAndRemainingKeystoresAreLoaded(
      final @TempDir Path keystoreDir, final @TempDir Path passwordDir) {
    KeystoreUtil.createKeystoreFile(KEY_PAIR_1, keystoreDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystore(KEY_PAIR_2, keystoreDir, passwordDir, KEYSTORE_PASSWORD_2);

    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    final Collection<ArtifactSigner> signers = result.artifactSigners();
    assertThat(signers).hasSize(1);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_2);

    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.failedFiles())
        .containsExactly(keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".json").toString());
    assertThat(result.sourceFailed()).isFalse();
  }

  @Test
  void invalidKeystoreIsIgnoredAndRemainingKeystoresAreLoaded(
      final @TempDir Path keystoreDir, final @TempDir Path passwordDir) throws IOException {
    Files.writeString(keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".json"), "{}");
    KeystoreUtil.createKeystorePasswordFile(KEY_PAIR_1, passwordDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystore(KEY_PAIR_2, keystoreDir, passwordDir, KEYSTORE_PASSWORD_2);

    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    final Collection<ArtifactSigner> signers = result.artifactSigners();
    assertThat(signers).hasSize(1);
    assertThatSignerHasPublicKey(signers, KEY_PAIR_2);

    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.failedFiles())
        .containsExactly(keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".json").toString());
  }

  @Test
  void invalidKeystoreDirectoryReportsSourceFailure(
      final @TempDir Path keystoreDir, final @TempDir Path passwordDir) {
    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(
            keystoreDir.resolve("invalidKeystorePath"), passwordDir);
    assertThat(result.artifactSigners()).isEmpty();
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.sourceFailed()).isTrue();
  }

  @Test
  void invalidKeystorePasswordFileReportsSourceFailure(final @TempDir Path tempDir)
      throws IOException {
    final Path keystoreDir = tempDir.resolve("keystores");
    Files.createDirectory(keystoreDir);

    final SignerSourceResult result =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordFile(
            keystoreDir, tempDir.resolve("invalidPasswordFilePath"));
    assertThat(result.artifactSigners()).isEmpty();
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.sourceFailed()).isTrue();
  }

  @Test
  void unreadablePasswordFileReportsSourceFailure(final @TempDir Path tempDir) throws IOException {
    final Path keystoreDir = Files.createDirectory(tempDir.resolve("keystores"));
    KeystoreUtil.createKeystoreFile(KEY_PAIR_1, keystoreDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystoreFile(KEY_PAIR_2, keystoreDir, KEYSTORE_PASSWORD_1);
    final Path passwordFile =
        Files.writeString(tempDir.resolve("password.txt"), KEYSTORE_PASSWORD_1);

    assertThat(
            BlsKeystoreBulkLoader.loadKeystoresUsingPasswordFile(keystoreDir, passwordFile)
                .artifactSigners())
        .hasSize(2);

    Files.delete(passwordFile);
    final SignerSourceResult errored =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordFile(keystoreDir, passwordFile);
    assertThat(errored.artifactSigners()).isEmpty();
    assertThat(errored.errorCount()).isEqualTo(1);
    assertThat(errored.sourceFailed()).isTrue();

    Files.writeString(passwordFile, KEYSTORE_PASSWORD_1);
    Files.delete(keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".json"));
    final SignerSourceResult recovered =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordFile(keystoreDir, passwordFile);
    assertThat(recovered.artifactSigners()).hasSize(1);
    assertThatSignerHasPublicKey(recovered.artifactSigners(), KEY_PAIR_2);
    assertThat(recovered.errorCount()).isZero();
  }

  @Test
  void failingKeystoreIsReportedAsFailedFileWhileDeletedKeystoreIsDropped(
      final @TempDir Path keystoreDir, final @TempDir Path passwordDir) throws IOException {
    KeystoreUtil.createKeystore(KEY_PAIR_1, keystoreDir, passwordDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystore(KEY_PAIR_2, keystoreDir, passwordDir, KEYSTORE_PASSWORD_2);
    assertThat(
            BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir)
                .artifactSigners())
        .hasSize(2);

    final Path keystore1 = keystoreDir.resolve(KEY_PAIR_1.getPublicKey() + ".json");
    Files.writeString(keystore1, "{}");
    Files.delete(keystoreDir.resolve(KEY_PAIR_2.getPublicKey() + ".json"));
    Files.delete(passwordDir.resolve(KEY_PAIR_2.getPublicKey() + ".txt"));

    final SignerSourceResult errored =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    // the failed file is named, the deleted one is not: only the failed key is retained by the
    // provider
    assertThat(errored.artifactSigners()).isEmpty();
    assertThat(errored.failedFiles()).containsExactly(keystore1.toString());
    assertThat(errored.errorCount()).isEqualTo(1);
    assertThat(errored.sourceFailed()).isFalse();

    Files.delete(keystore1);
    final SignerSourceResult recovered =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    assertThat(recovered.artifactSigners()).isEmpty();
    assertThat(recovered.errorCount()).isZero();
  }

  @Test
  void missingKeystoreDirectoryReportsSourceFailure(final @TempDir Path tempDir)
      throws IOException {
    final Path keystoreDir = Files.createDirectory(tempDir.resolve("keystores"));
    final Path passwordDir = Files.createDirectory(tempDir.resolve("passwords"));
    KeystoreUtil.createKeystore(KEY_PAIR_1, keystoreDir, passwordDir, KEYSTORE_PASSWORD_1);
    KeystoreUtil.createKeystore(KEY_PAIR_2, keystoreDir, passwordDir, KEYSTORE_PASSWORD_2);
    assertThat(
            BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir)
                .artifactSigners())
        .hasSize(2);

    FileUtils.deleteDirectory(keystoreDir.toFile());

    final SignerSourceResult errored =
        BlsKeystoreBulkLoader.loadKeystoresUsingPasswordDir(keystoreDir, passwordDir);
    assertThat(errored.artifactSigners()).isEmpty();
    assertThat(errored.errorCount()).isEqualTo(1);
    assertThat(errored.sourceFailed()).isTrue();
  }

  private void assertThatSignerHasPublicKey(
      final Collection<ArtifactSigner> signers, final BLSKeyPair keyPair0) {
    assertThat(signers).anyMatch(s -> s.getIdentifier().equals(keyPair0.getPublicKey().toString()));
  }
}
