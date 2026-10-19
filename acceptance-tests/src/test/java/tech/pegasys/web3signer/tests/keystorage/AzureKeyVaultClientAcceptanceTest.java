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
package tech.pegasys.web3signer.tests.keystorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tech.pegasys.web3signer.keystorage.azure.AzureKeyVault.createUsingClientSecretCredentials;

import tech.pegasys.web3signer.dsl.azure.AzureKeyVaultEmulator;
import tech.pegasys.web3signer.dsl.azure.MockAzureAuthorityExtension;
import tech.pegasys.web3signer.keystorage.azure.AzureKeyVault;
import tech.pegasys.web3signer.keystorage.azure.AzureOverrides;
import tech.pegasys.web3signer.keystorage.common.MappedResults;

import java.net.URI;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.azure.security.keyvault.keys.models.KeyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AzureKeyVaultClientAcceptanceTest {
  private static final AzureKeyVaultEmulator EMULATOR = AzureKeyVaultEmulator.getInstance();
  private static final Map<String, String> FIXTURE_TAG =
      Map.of(AzureKeyVaultEmulator.FIXTURE_TAG_KEY, AzureKeyVaultEmulator.FIXTURE_TAG_VALUE);

  @RegisterExtension
  static final MockAzureAuthorityExtension MOCK_AUTHORITY = new MockAzureAuthorityExtension();

  @Test
  void fetchesExistingAndMissingSecrets() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final AzureKeyVault vault = createVault(executor, "unused", "unused");

      assertThat(vault.fetchSecret(AzureKeyVaultEmulator.BLS_SECRET_NAME)).isPresent();
      assertThat(vault.fetchSecret("missing-secret")).isEmpty();
    }
  }

  @Test
  void mapsSecretsAndKeysByTag() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final AzureKeyVault vault = createVault(executor, "unused", "unused");

      final MappedResults<SimpleEntry<String, String>> secrets =
          vault.mapSecrets(SimpleEntry::new, FIXTURE_TAG);
      assertThat(secrets.getErrorCount()).isZero();
      assertThat(secrets.getValues())
          .extracting(SimpleEntry::getKey)
          .contains(
              AzureKeyVaultEmulator.BLS_SECRET_NAME, AzureKeyVaultEmulator.BLS_TAGGED_SECRET_NAME);

      final MappedResults<String> keys =
          vault.mapKeyProperties(KeyProperties::getName, FIXTURE_TAG);
      assertThat(keys.getErrorCount()).isZero();
      assertThat(keys.getValues())
          .containsExactlyInAnyOrder(
              AzureKeyVaultEmulator.SECP_18_KEY_NAME,
              AzureKeyVaultEmulator.SECP_19_KEY_NAME,
              AzureKeyVaultEmulator.SECP_20_TAGGED_KEY_NAME);
    }
  }

  @Test
  void recordsMapperFailures() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final AzureKeyVault vault = createVault(executor, "unused", "unused");

      final MappedResults<SimpleEntry<String, String>> secrets =
          vault.mapSecrets(
              (name, value) -> {
                throw new IllegalStateException("mapping failed");
              },
              FIXTURE_TAG);
      assertThat(secrets.getValues()).isEmpty();
      assertThat(secrets.getErrorCount()).isPositive();

      final MappedResults<String> keys =
          vault.mapKeyProperties(
              key -> {
                throw new IllegalStateException("mapping failed");
              },
              FIXTURE_TAG);
      assertThat(keys.getValues()).isEmpty();
      assertThat(keys.getErrorCount()).isPositive();
    }
  }

  @Test
  void rejectsInvalidClientCredentials() {
    MOCK_AUTHORITY.rejectCredentials("invalid", "invalid");

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final AzureKeyVault vault = createVault(executor, "invalid", "invalid");

      assertThatThrownBy(() -> vault.fetchSecret(AzureKeyVaultEmulator.BLS_SECRET_NAME))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Invalid client credentials");
    }
  }

  private static AzureKeyVault createVault(
      final ExecutorService executor, final String clientId, final String clientSecret) {
    return createUsingClientSecretCredentials(
        clientId,
        clientSecret,
        AzureKeyVaultEmulator.TENANT_ID,
        "unused",
        executor,
        60,
        emulatorOverrides());
  }

  private static AzureOverrides emulatorOverrides() {
    return new AzureOverrides(
        Optional.of(URI.create(EMULATOR.getVaultUrl())),
        Optional.of(URI.create(MOCK_AUTHORITY.getAuthorityHostUrl())),
        Optional.of(EMULATOR.getTrustCertificatePath()));
  }
}
