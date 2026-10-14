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
package tech.pegasys.web3signer.tests.bulkloading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static tech.pegasys.web3signer.core.config.HealthCheckNames.KEYS_CHECK_GCP_BULK_LOADING;
import static tech.pegasys.web3signer.dsl.utils.HealthCheckResultUtil.getHealtcheckKeysLoaded;
import static tech.pegasys.web3signer.dsl.utils.HealthCheckResultUtil.getHealthcheckErrorCount;
import static tech.pegasys.web3signer.dsl.utils.HealthCheckResultUtil.getHealthcheckStatusValue;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.web3signer.GcpSecretManagerUtil;
import tech.pegasys.web3signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.web3signer.signing.KeyType;
import tech.pegasys.web3signer.signing.config.GcpSecretManagerParameters;
import tech.pegasys.web3signer.signing.config.GcpSecretManagerParametersBuilder;
import tech.pegasys.web3signer.tests.AcceptanceTestBase;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import io.restassured.http.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Disabled("Google Secrets Manager acceptance test temporarily disabled")
@EnabledIfEnvironmentVariable(
    named = "GCP_PROJECT_ID",
    matches = ".+",
    disabledReason = "GCP_PROJECT_ID env variable is required")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // same instance is shared across test methods
public class GcpSecretManagerAcceptanceTest extends AcceptanceTestBase {
  private static final Logger LOG = LogManager.getLogger();
  private static final String GCP_PROJECT_ID = System.getenv("GCP_PROJECT_ID");

  private GcpSecretManagerUtil gcpSecretManagerUtil;
  private final List<BLSKeyPair> blsKeyPairs = new ArrayList<>();
  private final List<String> secretNames = new ArrayList<>();

  @BeforeAll
  void setupGcpResources() throws IOException {
    gcpSecretManagerUtil = new GcpSecretManagerUtil(GCP_PROJECT_ID);
    final SecureRandom secureRandom = new SecureRandom();

    for (int i = 0; i < 4; i++) {
      final BLSKeyPair blsKeyPair = BLSKeyPair.random(secureRandom);
      String secretName =
          gcpSecretManagerUtil.createSecret(
              "Secret%d-%s".formatted(i, blsKeyPair.getPublicKey().toString()),
              blsKeyPair.getSecretKey().toBytes().toHexString());
      blsKeyPairs.add(blsKeyPair);
      secretNames.add(secretName);
    }
  }

  @ParameterizedTest(name = "{index} - Using config file: {0}")
  @ValueSource(booleans = {true, false})
  void secretsAreLoadedFromGCPSecretManagerAndReportedByPublicApi(final boolean useConfigFile) {
    final GcpSecretManagerParameters gcpSecretManagerParameters =
        GcpSecretManagerParametersBuilder.aGcpParameters()
            .withEnabled(true)
            .withProjectId(GCP_PROJECT_ID)
            .withFilter("name:Secret0 OR name:Secret1")
            .build();

    final SignerConfigurationBuilder configBuilder =
        new SignerConfigurationBuilder()
            .withUseConfigFile(useConfigFile)
            .withMode("eth2")
            .withGcpParameters(gcpSecretManagerParameters);

    startSigner(configBuilder.build());

    final String healthCheckJsonBody = signer.healthcheck().body().asString();
    int keysLoaded = getHealtcheckKeysLoaded(healthCheckJsonBody, KEYS_CHECK_GCP_BULK_LOADING);

    assertThat(keysLoaded).isEqualTo(2);

    signer
        .callApiPublicKeys(KeyType.BLS)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body(
            "",
            containsInAnyOrder(
                blsKeyPairs.get(0).getPublicKey().toString(),
                blsKeyPairs.get(1).getPublicKey().toString()),
            "",
            hasSize(2));
  }

  @Test
  void healthCheckErrorCountWhenInvalidCredentialsAreUsed() {
    final boolean useConfigFile = false;
    final GcpSecretManagerParameters invalidGcpParams =
        GcpSecretManagerParametersBuilder.aGcpParameters()
            .withEnabled(true)
            .withProjectId("NON_EXISTING_PROJECT")
            .build();

    final SignerConfigurationBuilder configBuilder =
        new SignerConfigurationBuilder()
            .withUseConfigFile(useConfigFile)
            .withMode("eth2")
            .withGcpParameters(invalidGcpParams);

    startSigner(configBuilder.build());

    final String healthCheckJsonBody = signer.healthcheck().body().asString();

    int keysLoaded = getHealtcheckKeysLoaded(healthCheckJsonBody, KEYS_CHECK_GCP_BULK_LOADING);
    int errorCount = getHealthcheckErrorCount(healthCheckJsonBody, KEYS_CHECK_GCP_BULK_LOADING);

    assertThat(keysLoaded).isEqualTo(0);
    assertThat(errorCount).isEqualTo(1);
    assertThat(getHealthcheckStatusValue(healthCheckJsonBody)).isEqualTo("DOWN");
  }

  @AfterAll
  void cleanUpAwsResources() {
    if (gcpSecretManagerUtil != null) {
      secretNames.forEach(
          secretName -> {
            try {
              gcpSecretManagerUtil.deleteSecret(secretName);
            } catch (final RuntimeException e) {
              LOG.warn(
                  "Unexpected error while deleting key {}{}: {}",
                  gcpSecretManagerUtil.getSecretsManagerPrefix(),
                  secretName,
                  e.getMessage());
            }
          });
      gcpSecretManagerUtil.close();
    }
  }
}
