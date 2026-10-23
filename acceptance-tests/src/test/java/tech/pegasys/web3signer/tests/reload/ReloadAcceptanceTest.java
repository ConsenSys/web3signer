/*
 * Copyright 2025 ConsenSys AG.
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
package tech.pegasys.web3signer.tests.reload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static tech.pegasys.web3signer.signing.KeyType.BLS;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.web3signer.BLSTestUtil;
import tech.pegasys.web3signer.KeystoreUtil;
import tech.pegasys.web3signer.bls.keystore.model.KdfFunction;
import tech.pegasys.web3signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.web3signer.dsl.utils.DefaultKeystoresParameters;
import tech.pegasys.web3signer.dsl.utils.MetadataFileHelpers;
import tech.pegasys.web3signer.tests.signing.SigningAcceptanceTestBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.restassured.http.ContentType;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ReloadAcceptanceTest extends SigningAcceptanceTestBase {
  private static final MetadataFileHelpers METADATA_FILE_HELPERS = new MetadataFileHelpers();

  @Test
  void reloadAcceptsRequestAndReturns202WithAcceptedMessage() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    signer
        .callReload()
        .then()
        .statusCode(202)
        .contentType(ContentType.JSON)
        .body("code", equalTo(202))
        .body("message", containsString("background"));
  }

  @Test
  void reloadRejectsSecondRequestWithConflictMessageWhenReloadInProgress() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create encrypted files to slow down reload
    for (int i = 0; i < 5; i++) {
      final BLSKeyPair blsKeyPair = BLSTestUtil.randomKeyPair(i);
      final Path keyConfigFile =
          testDirectory.resolve(blsKeyPair.getPublicKey().toHexString() + ".yaml");
      METADATA_FILE_HELPERS.createKeyStoreYamlFileAt(keyConfigFile, blsKeyPair, KdfFunction.PBKDF2);
    }

    // First reload accepted
    signer.callReload().then().statusCode(202);

    // Second reload rejected with helpful message
    signer
        .callReload()
        .then()
        .statusCode(409)
        .contentType(ContentType.JSON)
        .body("code", equalTo(409))
        .body("message", containsString("already in progress"));
  }

  @Test
  void reloadAllowsNewRequestAfterPreviousCompletes() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create a few files
    for (int i = 0; i < 3; i++) {
      final BLSKeyPair blsKeyPair = BLSTestUtil.randomKeyPair(i);
      final Path keyConfigFile =
          testDirectory.resolve(blsKeyPair.getPublicKey().toHexString() + ".yaml");
      METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
          keyConfigFile, blsKeyPair.getSecretKey().toBytes().toHexString(), BLS);
    }

    // First reload
    signer.callReload().then().statusCode(202);

    // Wait for completion
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(1, TimeUnit.SECONDS)
        .until(() -> signer.listPublicKeys(BLS).size() == 3);

    // Second reload should now succeed
    signer.callReload().then().statusCode(202).body("code", equalTo(202));
  }

  @Test
  void reloadPicksUpFileSystemChanges() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");

    // Start with 2 keystores
    final BLSKeyPair blsKeyPair1 = BLSTestUtil.randomKeyPair(1);
    final Path keyConfigFile1 =
        testDirectory.resolve(blsKeyPair1.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        keyConfigFile1, blsKeyPair1.getSecretKey().toBytes().toHexString(), BLS);

    final BLSKeyPair blsKeyPair2 = BLSTestUtil.randomKeyPair(2);
    final Path keyConfigFile2 =
        testDirectory.resolve(blsKeyPair2.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        keyConfigFile2, blsKeyPair2.getSecretKey().toBytes().toHexString(), BLS);

    startSigner(builder.build());

    // Verify initial state
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> signer.listPublicKeys(BLS).size() == 2);

    // Add one, delete one, modify one
    final BLSKeyPair blsKeyPair3 = BLSTestUtil.randomKeyPair(3);
    final Path keyConfigFile3 =
        testDirectory.resolve(blsKeyPair3.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        keyConfigFile3, blsKeyPair3.getSecretKey().toBytes().toHexString(), BLS); // Add

    keyConfigFile2.toFile().delete(); // Delete

    final BLSKeyPair blsKeyPair1Updated = BLSTestUtil.randomKeyPair(10);
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        keyConfigFile1, blsKeyPair1Updated.getSecretKey().toBytes().toHexString(), BLS); // Modify

    // Reload
    signer.callReload().then().statusCode(202);

    // Verify changes: should have keys 1(updated) and 3, not key 2
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(1, TimeUnit.SECONDS)
        .until(
            () -> {
              var keys = signer.listPublicKeys(BLS);
              return keys.size() == 2
                  && keys.contains(blsKeyPair1Updated.getPublicKey().toHexString())
                  && keys.contains(blsKeyPair3.getPublicKey().toHexString());
            });

    assertThat(signer.listPublicKeys(BLS))
        .hasSize(2)
        .contains(blsKeyPair1Updated.getPublicKey().toHexString())
        .contains(blsKeyPair3.getPublicKey().toHexString())
        .doesNotContain(blsKeyPair1.getPublicKey().toHexString()) // Old version
        .doesNotContain(blsKeyPair2.getPublicKey().toHexString()); // Deleted
  }

  @Test
  void getReloadStatusReturnsIdleWhenNoReloadHasBeenTriggered() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    signer
        .getReloadStatus()
        .then()
        .statusCode(200)
        .body("status", equalTo("idle"))
        .body("lastOperationTime", nullValue())
        .body("lastError", nullValue());
  }

  @Test
  void getReloadStatusTransitionsFromIdleToCompleteDuringReload() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    signer
        .getReloadStatus()
        .then()
        .statusCode(200)
        .body("status", equalTo("idle"))
        .body("lastOperationTime", nullValue())
        .body("lastError", nullValue());

    // Create encrypted files to slow down reload
    for (int i = 0; i < 10; i++) {
      final BLSKeyPair blsKeyPair = BLSTestUtil.randomKeyPair(i);
      final Path keyConfigFile =
          testDirectory.resolve(blsKeyPair.getPublicKey().toHexString() + ".yaml");
      METADATA_FILE_HELPERS.createKeyStoreYamlFileAt(keyConfigFile, blsKeyPair, KdfFunction.PBKDF2);
    }

    // Trigger reload
    signer.callReload().then().statusCode(202);

    // Immediately check status - should be running
    signer
        .getReloadStatus()
        .then()
        .statusCode(200)
        .body("status", equalTo("running"))
        .body("lastOperationTime", notNullValue())
        .body("lastError", nullValue());

    // Wait for completion using status endpoint
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"))
                    .body("lastOperationTime", notNullValue())
                    .body("lastError", nullValue()));
  }

  @Test
  void getReloadStatusCanBeCalledMultipleTimesDuringReload() {
    // Tests concurrent GET requests don't interfere with reload
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create encrypted files to slow down reload
    for (int i = 0; i < 10; i++) {
      final BLSKeyPair blsKeyPair = BLSTestUtil.randomKeyPair(i);
      final Path keyConfigFile =
          testDirectory.resolve(blsKeyPair.getPublicKey().toHexString() + ".yaml");
      METADATA_FILE_HELPERS.createKeyStoreYamlFileAt(keyConfigFile, blsKeyPair, KdfFunction.PBKDF2);
    }

    signer.callReload().then().statusCode(202);

    // Poll status multiple times - all should succeed with "running"
    for (int i = 0; i < 5; i++) {
      signer.getReloadStatus().then().statusCode(200).body("status", equalTo("running"));
    }
  }

  @Test
  void lastOperationTimeIsUpdatedOnEachReload() {
    // Tests timestamp tracking across multiple reloads
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // First reload
    signer.callReload().then().statusCode(202);

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> signer.getReloadStatus().then().body("status", equalTo("completed")));

    final String firstTimestamp =
        signer.getReloadStatus().then().extract().path("lastOperationTime");

    // Wait to ensure timestamp difference
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Second reload
    signer.callReload().then().statusCode(202);

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> signer.getReloadStatus().then().body("status", equalTo("completed")));

    final String secondTimestamp =
        signer.getReloadStatus().then().extract().path("lastOperationTime");

    assertThat(secondTimestamp).isNotEqualTo(firstTimestamp);
  }

  @Test
  void getReloadStatusReturnsCompletedWithErrorsWhenSomeKeystoresFail() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create a mix of valid and invalid keystore files
    final BLSKeyPair validKeyPair1 = BLSTestUtil.randomKeyPair(1);
    final Path validKeyConfigFile1 =
        testDirectory.resolve(validKeyPair1.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        validKeyConfigFile1, validKeyPair1.getSecretKey().toBytes().toHexString(), BLS);

    // Create invalid keystore files (malformed hex, invalid YAML, etc.)
    final Path invalidKeyConfigFile1 = testDirectory.resolve("invalid1.yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        invalidKeyConfigFile1, "not-a-valid-hex-string", BLS);

    final Path invalidKeyConfigFile2 = testDirectory.resolve("invalid2.yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        invalidKeyConfigFile2, "GGGGGGGGGGGGGGGG", BLS); // Invalid hex characters

    final BLSKeyPair validKeyPair2 = BLSTestUtil.randomKeyPair(2);
    final Path validKeyConfigFile2 =
        testDirectory.resolve(validKeyPair2.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        validKeyConfigFile2, validKeyPair2.getSecretKey().toBytes().toHexString(), BLS);

    // Trigger reload
    signer.callReload().then().statusCode(202);

    // Wait for completion with errors
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed_with_errors"))
                    .body("lastOperationTime", notNullValue())
                    .body("lastError", containsString("2 signer loading error")));

    // Verify that valid signers were loaded despite errors
    assertThat(signer.listPublicKeys(BLS))
        .hasSize(2)
        .contains(validKeyPair1.getPublicKey().toHexString())
        .contains(validKeyPair2.getPublicKey().toHexString());
  }

  @Test
  void getReloadStatusReturnsCompletedWhenAllKeystoresLoadSuccessfully() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create only valid keystore files
    final BLSKeyPair validKeyPair1 = BLSTestUtil.randomKeyPair(1);
    final Path validKeyConfigFile1 =
        testDirectory.resolve(validKeyPair1.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        validKeyConfigFile1, validKeyPair1.getSecretKey().toBytes().toHexString(), BLS);

    final BLSKeyPair validKeyPair2 = BLSTestUtil.randomKeyPair(2);
    final Path validKeyConfigFile2 =
        testDirectory.resolve(validKeyPair2.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        validKeyConfigFile2, validKeyPair2.getSecretKey().toBytes().toHexString(), BLS);

    // Trigger reload
    signer.callReload().then().statusCode(202);

    // Wait for completion - should be "completed" not "completed_with_errors"
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"))
                    .body("lastOperationTime", notNullValue())
                    .body("lastError", nullValue())); // No error message

    // Verify all signers loaded
    assertThat(signer.listPublicKeys(BLS)).hasSize(2);
  }

  @Test
  void previousErrorIsClearedAfterSuccessfulReload() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // First reload: Create invalid keystores to cause errors
    final Path invalidKeyConfigFile1 = testDirectory.resolve("invalid1.yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        invalidKeyConfigFile1, "invalid-hex-content", BLS);

    final Path invalidKeyConfigFile2 = testDirectory.resolve("invalid2.yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(invalidKeyConfigFile2, "also-invalid", BLS);

    signer.callReload().then().statusCode(202);

    // Wait for completion with errors
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed_with_errors"))
                    .body("lastError", notNullValue()));

    // Fix the configuration: remove invalid files, add valid ones
    invalidKeyConfigFile1.toFile().delete();
    invalidKeyConfigFile2.toFile().delete();

    final BLSKeyPair validKeyPair = BLSTestUtil.randomKeyPair(1);
    final Path validKeyConfigFile =
        testDirectory.resolve(validKeyPair.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        validKeyConfigFile, validKeyPair.getSecretKey().toBytes().toHexString(), BLS);

    // Second reload: should succeed with no errors
    signer.callReload().then().statusCode(202);

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"))
                    .body("lastError", nullValue())); // Error cleared

    // Verify valid signer loaded
    assertThat(signer.listPublicKeys(BLS))
        .hasSize(1)
        .contains(validKeyPair.getPublicKey().toHexString());
  }

  @Test
  void reloadStatusShowsErrorCountForMultipleFailures() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create multiple invalid keystore files
    for (int i = 0; i < 5; i++) {
      final Path invalidKeyConfigFile = testDirectory.resolve("invalid" + i + ".yaml");
      METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
          invalidKeyConfigFile, "invalid-content-" + i, BLS);
    }

    // Add one valid keystore
    final BLSKeyPair validKeyPair = BLSTestUtil.randomKeyPair(1);
    final Path validKeyConfigFile =
        testDirectory.resolve(validKeyPair.getPublicKey().toHexString() + ".yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        validKeyConfigFile, validKeyPair.getSecretKey().toBytes().toHexString(), BLS);

    // Trigger reload
    signer.callReload().then().statusCode(202);

    // Wait for completion and verify error count in message
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed_with_errors"))
                    .body("lastError", containsString("5 signer loading error")));

    // Verify the one valid signer was loaded
    assertThat(signer.listPublicKeys(BLS))
        .hasSize(1)
        .contains(validKeyPair.getPublicKey().toHexString());
  }

  @Test
  void reloadWithAllInvalidKeystoresShowsCompletedWithErrors() {
    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder.withKeyStoreDirectory(testDirectory).withMode("eth2");
    startSigner(builder.build());

    // Create only invalid keystore files (no valid ones)
    for (int i = 0; i < 3; i++) {
      final Path invalidKeyConfigFile = testDirectory.resolve("invalid" + i + ".yaml");
      METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
          invalidKeyConfigFile, "completely-invalid", BLS);
    }

    // Trigger reload
    signer.callReload().then().statusCode(202);

    // Wait for completion with errors
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed_with_errors"))
                    .body("lastError", containsString("3 signer loading error")));

    // Verify no signers were loaded
    assertThat(signer.listPublicKeys(BLS)).isEmpty();
  }

  @Test
  void reloadWithUnreadablePasswordFileKeepsKeysAndLaterReloadRemovesDeletedKeystore(
      @TempDir final Path tempDir) throws IOException {
    final BLSKeyPair keyPair1 = BLSTestUtil.randomKeyPair(1);
    final BLSKeyPair keyPair2 = BLSTestUtil.randomKeyPair(2);
    final Path keystoreDir = Files.createDirectory(tempDir.resolve("keystores"));
    KeystoreUtil.createKeystoreFile(keyPair1, keystoreDir, "password1");
    KeystoreUtil.createKeystoreFile(keyPair2, keystoreDir, "password1");
    final Path passwordFile = Files.writeString(tempDir.resolve("password.txt"), "password1");

    final SignerConfigurationBuilder builder = new SignerConfigurationBuilder();
    builder
        .withMode("eth2")
        .withKeystoresParameters(new DefaultKeystoresParameters(keystoreDir, null, passwordFile));
    startSigner(builder.build());

    assertThat(signer.listPublicKeys(BLS)).hasSize(2);

    // password file becomes unreadable: both keys are kept, the reload reports the error
    Files.delete(passwordFile);
    signer.callReload().then().statusCode(202);
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed_with_errors"))
                    .body("lastError", containsString("1 signer loading error")));
    assertThat(signer.listPublicKeys(BLS))
        .containsExactlyInAnyOrder(
            keyPair1.getPublicKey().toHexString(), keyPair2.getPublicKey().toHexString());

    // password file is restored and keystore 2 is deleted: only key 2 goes away
    Files.writeString(passwordFile, "password1");
    Files.delete(keystoreDir.resolve(keyPair2.getPublicKey() + ".json"));
    signer.callReload().then().statusCode(202);
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                signer
                    .getReloadStatus()
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"))
                    .body("lastError", nullValue()));
    assertThat(signer.listPublicKeys(BLS)).containsExactly(keyPair1.getPublicKey().toHexString());
  }
}
