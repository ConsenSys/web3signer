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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.pegasys.web3signer.slashingprotection.dao.MetadataDao;
import tech.pegasys.web3signer.slashingprotection.dao.ValidatorsDao;
import tech.pegasys.web3signer.slashingprotection.interchange.InterchangeJsonProvider;
import tech.pegasys.web3signer.slashingprotection.model.AttestationTestModel;
import tech.pegasys.web3signer.slashingprotection.model.BlockTestModel;
import tech.pegasys.web3signer.slashingprotection.model.Step;
import tech.pegasys.web3signer.slashingprotection.model.TestFileModel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import db.DatabaseUtil;
import db.DatabaseUtil.TestDatabaseInfo;
import dsl.SignedArtifacts;
import dsl.TestSlashingProtectionParameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class ReferenceTestRunner {

  private static final Logger LOG = LogManager.getLogger();

  private static final String USERNAME = "postgres";
  private static final String PASSWORD = "postgres";
  private static final List<String> TESTS_TO_IGNORE =
      List.of("multiple_interchanges_single_validator_multiple_blocks_out_of_order");

  private static final ObjectMapper OBJECT_MAPPER = new InterchangeJsonProvider().getJsonMapper();
  private final ValidatorsDao validators = new ValidatorsDao();

  private SlashingProtectionContext slashingProtectionContext;
  private Jdbi jdbi;

  public void setup() {
    final TestDatabaseInfo testDatabaseInfo = DatabaseUtil.create();
    final SlashingProtectionParameters slashingProtectionParameters =
        new TestSlashingProtectionParameters(testDatabaseInfo.databaseUrl(), USERNAME, PASSWORD);
    slashingProtectionContext =
        SlashingProtectionContextFactory.create(slashingProtectionParameters);
    jdbi = testDatabaseInfo.getJdbi();
  }

  @TestFactory
  public Collection<DynamicTest> executeEachReferenceTestFile() {
    final URL refTestPath =
        Resources.getResource(
            Path.of("slashing-protection-interchange-tests", "tests", "generated").toString());
    final Path testFilesPath = Path.of(refTestPath.getPath());

    try {
      try (final Stream<Path> files = Files.list(testFilesPath)) {
        return files
            .map(this::readTestModel)
            .filter(model -> !TESTS_TO_IGNORE.contains(model.getName()))
            .map(model -> DynamicTest.dynamicTest(model.getName(), () -> executeFile(model)))
            .collect(Collectors.toList());
      }
    } catch (final IOException e) {
      throw new RuntimeException("Failed to create dynamic tests", e);
    }
  }

  private TestFileModel readTestModel(final Path tf) {
    try {
      return OBJECT_MAPPER.readValue(tf.toFile(), TestFileModel.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void executeFile(final TestFileModel model) throws IOException {
    setup();
    for (final Step step : model.getSteps()) {
      final String interchangeContent =
          OBJECT_MAPPER.writeValueAsString(step.getInterchangeContent());

      final Bytes32 gvr = Bytes32.fromHexString(model.getGenesisValidatorsRoot());

      jdbi.useHandle(
          h -> {
            final MetadataDao metadataDao = new MetadataDao();
            if (metadataDao.findGenesisValidatorsRoot(h).isEmpty()) {
              metadataDao.insertGenesisValidatorsRoot(h, gvr);
            }
          });

      // web3signer doesn't allow for partial imports, so - if it is expected, then
      // expect import to throw.
      if (step.isShouldSucceed()) {
        slashingProtectionContext
            .getSlashingProtection()
            .importData(new ByteArrayInputStream(interchangeContent.getBytes(UTF_8)));
        verifyImport(step, gvr);
      } else {
        assertThatThrownBy(
                () ->
                    slashingProtectionContext
                        .getSlashingProtection()
                        .importData(new ByteArrayInputStream(interchangeContent.getBytes(UTF_8))))
            .isInstanceOf(RuntimeException.class);
      }
    }
  }

  private void verifyImport(final Step step, final Bytes32 gvr) {
    final List<String> validatorsInModel =
        step.getInterchangeContent().getSignedArtifacts().stream()
            .map(SignedArtifacts::getPublicKey)
            .distinct()
            .collect(Collectors.toList());

    final List<String> publicKeysInDb = getValidatorPublicKeysFromDb();

    assertThat(publicKeysInDb).containsAll(validatorsInModel);

    // need to register the validators with slashingProtection before testing blocks/attestations
    slashingProtectionContext
        .getRegisteredValidators()
        .registerValidators(
            validatorsInModel.stream().map(Bytes::fromHexString).collect(Collectors.toList()));

    validateAttestations(step.getAttestations(), gvr);
    validateBlocks(step.getBlocks(), gvr);
  }

  private List<String> getValidatorPublicKeysFromDb() {

    return jdbi.withHandle(
        h ->
            validators
                .findAllValidators(h)
                .map(v -> v.getPublicKey().toHexString())
                .collect(Collectors.toList()));
  }

  private void validateAttestations(
      final List<AttestationTestModel> attestations, final Bytes32 gvr) {
    for (int i = 0; i < attestations.size(); i++) {
      final AttestationTestModel attestation = attestations.get(i);
      final boolean result =
          slashingProtectionContext
              .getSlashingProtection()
              .maySignAttestation(
                  attestation.getPublicKey(),
                  attestation.getSigningRoot(),
                  attestation.getSourceEpoch(),
                  attestation.getTargetEpoch(),
                  gvr);
      if (!attestation.isShouldSucceed()) {
        assertThat(result).isFalse();
      } else if (!result) {
        LOG.warn(
            "Detected a valid signing condition which was rejected by slashing-protection (attestation {} for validator {})",
            i,
            attestation.getPublicKey());
      }
    }
  }

  private void validateBlocks(final List<BlockTestModel> blocks, final Bytes32 gvr) {

    for (int i = 0; i < blocks.size(); i++) {
      final BlockTestModel block = blocks.get(i);
      final boolean result =
          slashingProtectionContext
              .getSlashingProtection()
              .maySignBlock(block.getPublicKey(), block.getSigningRoot(), block.getSlot(), gvr);
      // NOTE: Ref tests are only fail if you sign something which is flagged as "!should_Succeed".
      // (i.e. a "reject" response is always correct)
      if (!block.isShouldSucceed()) {
        assertThat(result).isFalse();
      } else if (!result) {
        LOG.warn(
            "Detected a valid signing condition which was rejected by slashing-protection (block {} for validator {})",
            i,
            block.getPublicKey());
      }
    }
  }
}
