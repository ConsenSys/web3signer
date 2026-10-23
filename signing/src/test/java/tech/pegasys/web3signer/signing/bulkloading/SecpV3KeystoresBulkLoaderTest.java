/*
 * Copyright 2023 ConsenSys AG.
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

import tech.pegasys.web3signer.signing.config.SignerSourceResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.crypto.WalletUtils;

public class SecpV3KeystoresBulkLoaderTest {

  @TempDir Path keystoreDir;
  @TempDir Path passwordDir;

  @BeforeEach
  void initV3KeystoresAndPasswordFiles() throws Exception {
    for (int i = 0; i < 4; i++) {
      final String fileName =
          WalletUtils.generateLightNewWalletFile("test123", keystoreDir.toFile());

      final Path passwordFile =
          passwordDir.resolve(fileName.substring(0, fileName.lastIndexOf(".json")) + ".txt");
      Files.writeString(passwordFile, "test123");

      // write files in wallet dir that will be ignored by bulk loading logic
      Files.writeString(keystoreDir.resolve(i + ".txt"), "ignored");
    }
  }

  @Test
  void loadSecpV3KeystoresWithPasswordFilesFromDir() {
    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordDir);

    assertThat(results.artifactSigners()).hasSize(4);
    assertThat(results.errorCount()).isZero();
    assertThat(results.failedFiles()).isEmpty();
    assertThat(results.sourceFailed()).isFalse();

    // each signer names the keystore file it was loaded from
    assertThat(results.signers())
        .allSatisfy(
            loaded ->
                assertThat(loaded.file())
                    .hasValueSatisfying(
                        file -> assertThat(file).startsWith(keystoreDir.toString())));
  }

  @Test
  void loadSecpV3KeystoresWithPasswordFile() throws IOException {
    final Path passwordFile;
    try (Stream<Path> passwordFiles = Files.list(passwordDir)) {
      passwordFile = passwordFiles.findAny().orElseThrow();
    }

    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordFile);

    assertThat(results.artifactSigners()).hasSize(4);
    assertThat(results.errorCount()).isZero();
  }

  @Test
  void emptyResultsWhenKeystoresDirIsEmpty(@TempDir Path emptyDir) throws IOException {
    final Path passwordFile;
    try (Stream<Path> passwordFiles = Files.list(passwordDir)) {
      passwordFile = passwordFiles.findAny().orElseThrow();
    }

    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(emptyDir, passwordFile);

    assertThat(results.artifactSigners()).isEmpty();
    assertThat(results.errorCount()).isZero();
  }

  @Test
  void errorResultsWhenV3KeystoreFileIsInvalid() throws IOException {
    for (int i = 0; i < 4; i++) {
      Files.writeString(keystoreDir.resolve(i + ".json"), "invalid content");
    }

    final Path passwordFile;
    try (Stream<Path> passwordFiles = Files.list(passwordDir)) {
      passwordFile = passwordFiles.findAny().orElseThrow();
    }

    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordFile);

    assertThat(results.artifactSigners()).hasSize(4);
    assertThat(results.errorCount()).isEqualTo(4);
    assertThat(results.failedFiles()).hasSize(4);
    assertThat(results.sourceFailed()).isFalse();
  }

  @Test
  void errorResultsWhenPasswordIsInvalid() throws IOException {
    final Path passwordFile = Files.writeString(passwordDir.resolve("password.txt"), "invalid");

    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordFile);

    assertThat(results.artifactSigners()).isEmpty();
    assertThat(results.errorCount()).isEqualTo(4);
    assertThat(results.failedFiles()).hasSize(4);
    assertThat(results.sourceFailed()).isFalse();
  }

  @Test
  void errorResultsWhenPasswordFileIsMissing() {
    final Path passwordFile = passwordDir.resolve("password.txt");

    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordFile);

    assertThat(results.artifactSigners()).isEmpty();
    assertThat(results.errorCount()).isEqualTo(1);
    assertThat(results.sourceFailed()).isTrue();
  }

  @Test
  void errorResultsWhenCorrespondingPasswordFileIsMissing() throws IOException {
    // delete one password file
    try (Stream<Path> passwordFiles = Files.list(passwordDir)) {
      Files.delete(passwordFiles.findAny().orElseThrow());
    }

    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordDir);

    assertThat(results.artifactSigners()).hasSize(3);
    assertThat(results.errorCount()).isEqualTo(1);
    assertThat(results.failedFiles())
        .hasSize(1)
        .allSatisfy(file -> assertThat(file).endsWith(".json"));
  }

  @Test
  void errorResultsWhenPasswordDirIsEmpty(@TempDir Path emptyPasswordDir) {
    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(
            keystoreDir, emptyPasswordDir);

    assertThat(results.artifactSigners()).isEmpty();
    assertThat(results.errorCount()).isEqualTo(4);
    assertThat(results.sourceFailed()).isFalse();
  }

  @Test
  void errorResultsWhenPasswordDirIsMissing() {
    final SignerSourceResult results =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(
            keystoreDir, passwordDir.resolve("/invalid"));

    assertThat(results.artifactSigners()).isEmpty();
    assertThat(results.errorCount()).isEqualTo(1);
    assertThat(results.sourceFailed()).isTrue();
  }

  @Test
  void unreadablePasswordFileReportsSourceFailure() throws IOException {
    final Path passwordFile = Files.writeString(passwordDir.resolve("password.txt"), "test123");
    assertThat(
            SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(
                    keystoreDir, passwordFile)
                .artifactSigners())
        .hasSize(4);

    Files.delete(passwordFile);
    final SignerSourceResult errored =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordFile);
    assertThat(errored.artifactSigners()).isEmpty();
    assertThat(errored.errorCount()).isEqualTo(1);
    assertThat(errored.sourceFailed()).isTrue();

    Files.writeString(passwordFile, "test123");
    Files.delete(keystoreFiles().get(0));
    final SignerSourceResult recovered =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordFile);
    assertThat(recovered.artifactSigners()).hasSize(3);
    assertThat(recovered.errorCount()).isZero();
  }

  @Test
  void failingKeystoreIsReportedAsFailedFileWhileDeletedKeystoreIsDropped() throws IOException {
    assertThat(
            SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(
                    keystoreDir, passwordDir)
                .artifactSigners())
        .hasSize(4);

    final List<Path> keystores = keystoreFiles();
    final Path corrupted = keystores.get(0);
    final Path deleted = keystores.get(1);
    Files.writeString(corrupted, "invalid content");

    final SignerSourceResult errored =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordDir);
    assertThat(errored.artifactSigners()).hasSize(3);
    assertThat(errored.errorCount()).isEqualTo(1);
    assertThat(errored.failedFiles()).containsExactly(corrupted.toString());
    assertThat(errored.sourceFailed()).isFalse();

    // deleting another keystore drops its signer: only the failed file is named
    Files.delete(deleted);
    final SignerSourceResult afterDelete =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordDir);
    assertThat(afterDelete.artifactSigners()).hasSize(2);
    assertThat(afterDelete.errorCount()).isEqualTo(1);
    assertThat(afterDelete.failedFiles()).containsExactly(corrupted.toString());

    Files.delete(corrupted);
    final SignerSourceResult recovered =
        SecpV3KeystoresBulkLoader.loadV3KeystoresUsingPasswordFileOrDir(keystoreDir, passwordDir);
    assertThat(recovered.artifactSigners()).hasSize(2);
    assertThat(recovered.errorCount()).isZero();
  }

  private List<Path> keystoreFiles() throws IOException {
    try (Stream<Path> files = Files.list(keystoreDir)) {
      return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
    }
  }
}
