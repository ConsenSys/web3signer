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
package tech.pegasys.web3signer.slashingprotection;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.web3signer.slashingprotection.dao.ValidatorsDao;
import tech.pegasys.web3signer.slashingprotection.interchange.InterchangeModule;
import tech.pegasys.web3signer.slashingprotection.model.TestFileModel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import com.opentable.db.postgres.embedded.EmbeddedPostgres;
import dsl.SignedArtifacts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

@SuppressWarnings("UnusedVariable")
public class ReferenceTestRunner {

  private static final Logger LOG = LogManager.getLogger();

  private static final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new InterchangeModule())
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
  private final ValidatorsDao validators = new ValidatorsDao();

  private EmbeddedPostgres setup() throws IOException {
    final EmbeddedPostgres slashingDatabase = EmbeddedPostgres.start();

    final Flyway flyway =
        Flyway.configure()
            .locations("/migrations/postgresql/")
            .dataSource(slashingDatabase.getPostgresDatabase())
            .load();
    flyway.migrate();

    return slashingDatabase;
  }

  @Test
  void execute() throws IOException {
    final URL refTestPath =
        Resources.getResource(
            Path.of("slashing-protection-interchange-tests", "tests", "generated").toString());

    final Path testFilesPath = Path.of(refTestPath.getPath());
    try (Stream<Path> testFiles = Files.list(testFilesPath)) {
      testFiles.forEach(this::executeFile);
    }
  }

  private void executeFile(final Path inputFile) {
    LOG.info(inputFile.toString());
    try {
      final TestFileModel model = objectMapper.readValue(inputFile.toFile(), TestFileModel.class);
      final String interchangeContent =
          objectMapper.writeValueAsString(model.getInterchangeContent());

      final EmbeddedPostgres db = setup();
      final String databaseUrl =
          String.format("jdbc:postgresql://localhost:%d/postgres", db.getPort());

      final SlashingProtection slashingProtection =
          SlashingProtectionFactory.createSlashingProtection(databaseUrl, "postgres", "postgres");

      slashingProtection.importData(new ByteArrayInputStream(interchangeContent.getBytes(UTF_8)));

      final List<String> validatorsInImport =
          model.getInterchangeContent().getSignedArtifacts().stream()
              .map(SignedArtifacts::getPublicKey)
              .collect(Collectors.toList());

      final Jdbi jdbi = DbConnection.createConnection(databaseUrl, "postgres", "postgres");
      final List<String> publicKeysInDb =
          jdbi.inTransaction(
              h ->
                  validators
                      .findAllValidators(h)
                      .map(v -> v.getPublicKey().toHexString())
                      .collect(Collectors.toList()));

      assertThat(validatorsInImport).containsExactlyInAnyOrderElementsOf(publicKeysInDb);

      // need to register the validators with slashingProtection before testing blocks/attestations
      if (validatorsInImport.isEmpty()) {
        return;
      }
      slashingProtection.registerValidators(
          validatorsInImport.stream().map(Bytes::fromHexString).collect(Collectors.toList()));

      model
          .getAttestations()
          .forEach(
              attestation -> {
                final boolean result =
                    slashingProtection.maySignAttestation(
                        attestation.getPublickKey(),
                        attestation.getSigningRoot(),
                        attestation.getSourceEpoch(),
                        attestation.getTargetEpoch());
                assertThat(result).isEqualTo(attestation.isShouldSucceed());
              });

      model
          .getBlocks()
          .forEach(
              block -> {
                final boolean result =
                    slashingProtection.maySignBlock(
                        block.getPublickKey(), block.getSigningRoot(), block.getSlot());
                assertThat(result).isEqualTo(block.isShouldSucceed());
              });
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException("setup failed for test");
    }
  }
}