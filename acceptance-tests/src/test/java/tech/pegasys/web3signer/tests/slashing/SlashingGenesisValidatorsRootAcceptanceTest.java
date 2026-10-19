/*
 * Copyright 2026 ConsenSys AG.
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
import static tech.pegasys.web3signer.dsl.utils.Eth2RequestUtils.GENESIS_VALIDATORS_ROOT;
import static tech.pegasys.web3signer.dsl.utils.Eth2RequestUtils.createAttestationRequest;
import static tech.pegasys.web3signer.dsl.utils.WaitUtils.waitFor;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.web3signer.BLSTestUtil;
import tech.pegasys.web3signer.dsl.signer.Signer;
import tech.pegasys.web3signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.web3signer.dsl.utils.DatabaseUtil;
import tech.pegasys.web3signer.dsl.utils.MetadataFileHelpers;
import tech.pegasys.web3signer.signing.KeyType;
import tech.pegasys.web3signer.slashingprotection.dao.MetadataDao;
import tech.pegasys.web3signer.tests.AcceptanceTestBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.response.Response;
import org.apache.tuweni.bytes.Bytes32;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SlashingGenesisValidatorsRootAcceptanceTest extends AcceptanceTestBase {

  private static final MetadataFileHelpers METADATA_FILE_HELPERS = new MetadataFileHelpers();
  public static final String DB_USERNAME = "postgres";
  public static final String DB_PASSWORD = "postgres";

  private static final Bytes32 MAINNET_GENESIS_VALIDATORS_ROOT =
      Bytes32.fromHexString("0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95");
  private static final Bytes32 DIFFERENT_GENESIS_VALIDATORS_ROOT =
      Bytes32.fromHexString("0x04700007fabc8282644aed6d1c7c9e21d38a03a0c4ba193f3afe428824b3a673");

  private final BLSKeyPair keyPair = BLSTestUtil.randomKeyPair(0);

  @Test
  void knownNetworkGvrIsStoredAtStartupWithoutASigningRequest(@TempDir final Path testDirectory) {
    final DatabaseUtil.TestDatabaseInfo database = DatabaseUtil.create();

    startSigner(configBuilder(testDirectory, database).build());

    assertThat(findGenesisValidatorsRoot(database.getJdbi()))
        .contains(MAINNET_GENESIS_VALIDATORS_ROOT);
  }

  @Test
  void startupHaltsWhenDatabaseHoldsADifferentGvr(@TempDir final Path testDirectory) {
    // NB: This test fails when running with a ThreadRunner (i.e. in IDE) due to the
    // System.exit() call in the Web3signerApp (which exits the test, rather than terminating
    // the app (thus it work as expected with a ProcessRunner (i.e. when run from gradle)).

    final DatabaseUtil.TestDatabaseInfo database = DatabaseUtil.create();
    database
        .getJdbi()
        .useHandle(
            h ->
                new MetadataDao()
                    .insertGenesisValidatorsRoot(h, DIFFERENT_GENESIS_VALIDATORS_ROOT));

    final SignerConfigurationBuilder builder =
        configBuilder(testDirectory, database).withHttpPort(9000); // to ensure

    signer = new Signer(builder.build(), null);
    signer.start();
    waitFor(() -> assertThat(signer.isRunning()).isTrue());
    waitFor(() -> assertThat(signer.isRunning()).isFalse());
  }

  @Test
  void genesisStateOptionSetsGvrAtStartup(@TempDir final Path testDirectory) throws IOException {
    final DatabaseUtil.TestDatabaseInfo database = DatabaseUtil.create();
    final BeaconState state =
        new DataStructureUtil(TestSpecFactory.createMinimalPhase0()).randomBeaconState(UInt64.ZERO);
    final Path stateFile = testDirectory.resolve("state.ssz");
    Files.write(stateFile, state.sszSerialize().toArrayUnsafe());

    startSigner(
        configBuilder(testDirectory, database)
            .withNetwork("minimal")
            .withGenesisState(stateFile.toString())
            .build());

    assertThat(findGenesisValidatorsRoot(database.getJdbi()))
        .contains(state.getGenesisValidatorsRoot());
  }

  @Test
  void unknownNetworkWithoutGenesisStateStaysLazy(@TempDir final Path testDirectory)
      throws JsonProcessingException {
    final DatabaseUtil.TestDatabaseInfo database = DatabaseUtil.create();
    final Path keyConfigFile = testDirectory.resolve("keyfile.yaml");
    METADATA_FILE_HELPERS.createUnencryptedYamlFileAt(
        keyConfigFile, keyPair.getSecretKey().toBytes().toHexString(), KeyType.BLS);

    startSigner(configBuilder(testDirectory, database).withNetwork("minimal").build());

    assertThat(findGenesisValidatorsRoot(database.getJdbi())).isEmpty();

    final Response response =
        signer.eth2Sign(
            keyPair.getPublicKey().toString(), createAttestationRequest(5, 6, UInt64.ZERO));
    assertThat(response.getStatusCode()).isEqualTo(200);

    assertThat(findGenesisValidatorsRoot(database.getJdbi()))
        .contains(Bytes32.fromHexString(GENESIS_VALIDATORS_ROOT));
  }

  private SignerConfigurationBuilder configBuilder(
      final Path testDirectory, final DatabaseUtil.TestDatabaseInfo database) {
    return new SignerConfigurationBuilder()
        .withMode("eth2")
        .withSlashingEnabled(true)
        .withSlashingProtectionDbUsername(DB_USERNAME)
        .withSlashingProtectionDbPassword(DB_PASSWORD)
        .withSlashingProtectionDbUrl(database.databaseUrl())
        .withMetricsEnabled(true)
        .withKeyStoreDirectory(testDirectory);
  }

  private Optional<Bytes32> findGenesisValidatorsRoot(final Jdbi jdbi) {
    return jdbi.withHandle(h -> new MetadataDao().findGenesisValidatorsRoot(h));
  }
}
