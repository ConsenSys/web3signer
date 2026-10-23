/*
 * Copyright 2026 Consensys Incorporated.
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
package tech.pegasys.web3signer.tests.slashing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static tech.pegasys.web3signer.dsl.utils.Eth2RequestUtils.createAttestationRequest;
import static tech.pegasys.web3signer.signing.KeyType.BLS;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.web3signer.BLSTestUtil;
import tech.pegasys.web3signer.KeystoreUtil;
import tech.pegasys.web3signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.web3signer.dsl.utils.DatabaseUtil;
import tech.pegasys.web3signer.dsl.utils.DatabaseUtil.TestDatabaseInfo;
import tech.pegasys.web3signer.dsl.utils.DefaultKeystoresParameters;
import tech.pegasys.web3signer.tests.AcceptanceTestBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.tuweni.bytes.Bytes;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SlashingReloadAcceptanceTest extends AcceptanceTestBase {
  private static final String KEYSTORE_PASSWORD = "password1";

  private final BLSKeyPair keyPair1 = BLSTestUtil.randomKeyPair(1);
  private final BLSKeyPair keyPair2 = BLSTestUtil.randomKeyPair(2);

  @Test
  void erroredReloadKeepsValidatorsEnabledAndCleanReloadDisablesOnlyDeletedOnes(
      @TempDir final Path tempDir) throws IOException {
    final TestDatabaseInfo testDatabaseInfo = DatabaseUtil.create();
    final Jdbi jdbi = testDatabaseInfo.getJdbi();

    final Path keystoreDir = Files.createDirectory(tempDir.resolve("keystores"));
    KeystoreUtil.createKeystoreFile(keyPair1, keystoreDir, KEYSTORE_PASSWORD);
    KeystoreUtil.createKeystoreFile(keyPair2, keystoreDir, KEYSTORE_PASSWORD);
    final Path passwordFile = Files.writeString(tempDir.resolve("password.txt"), KEYSTORE_PASSWORD);

    final SignerConfigurationBuilder builder =
        new SignerConfigurationBuilder()
            .withMode("eth2")
            .withSlashingEnabled(true)
            .withSlashingProtectionDbUsername(DatabaseUtil.USERNAME)
            .withSlashingProtectionDbPassword(DatabaseUtil.PASSWORD)
            .withSlashingProtectionDbUrl(testDatabaseInfo.databaseUrl())
            .withNetwork("minimal")
            .withKeystoresParameters(
                new DefaultKeystoresParameters(keystoreDir, null, passwordFile));
    startSigner(builder.build());

    final String publicKey1 = keyPair1.getPublicKey().toString();
    final String publicKey2 = keyPair2.getPublicKey().toString();

    // both validators are registered and enabled at load
    assertThat(signer.listPublicKeys(BLS)).hasSize(2);
    signer.eth2Sign(publicKey1, createAttestationRequest(1, 2, UInt64.ZERO)).then().statusCode(200);
    assertThat(validators(jdbi)).hasSize(2);
    assertThat(enabled(jdbi, publicKey1)).isTrue();
    assertThat(enabled(jdbi, publicKey2)).isTrue();

    // password file becomes unreadable: the reload reports an error but nothing is disabled
    Files.delete(passwordFile);
    signer.callReload().then().statusCode(202);
    awaitReloadStatus("completed_with_errors");

    assertThat(enabled(jdbi, publicKey1)).isTrue();
    assertThat(enabled(jdbi, publicKey2)).isTrue();
    assertThat(signer.listPublicKeys(BLS)).hasSize(2);
    signer.eth2Sign(publicKey1, createAttestationRequest(2, 3, UInt64.ZERO)).then().statusCode(200);

    // password file restored and keystore 2 deleted: only the deleted key is disabled
    Files.writeString(passwordFile, KEYSTORE_PASSWORD);
    Files.delete(keystoreDir.resolve(publicKey2 + ".json"));
    signer.callReload().then().statusCode(202);
    awaitReloadStatus("completed");

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(enabled(jdbi, publicKey2)).isFalse());
    assertThat(enabled(jdbi, publicKey1)).isTrue();
    assertThat(signer.listPublicKeys(BLS)).containsExactly(publicKey1);
  }

  private void awaitReloadStatus(final String status) {
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> signer.getReloadStatus().then().statusCode(200).body("status", equalTo(status)));
  }

  private static List<Map<String, Object>> validators(final Jdbi jdbi) {
    return jdbi.withHandle(
        h -> h.select("SELECT public_key, enabled FROM validators").mapToMap().list());
  }

  private static boolean enabled(final Jdbi jdbi, final String publicKey) {
    final byte[] publicKeyBytes = Bytes.fromHexString(publicKey).toArray();
    return validators(jdbi).stream()
        .filter(row -> Arrays.equals((byte[]) row.get("public_key"), publicKeyBytes))
        .map(row -> (Boolean) row.get("enabled"))
        .findFirst()
        .orElseThrow();
  }
}
