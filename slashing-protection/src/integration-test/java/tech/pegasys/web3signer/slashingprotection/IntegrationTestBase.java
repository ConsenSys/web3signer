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

import static db.DatabaseUtil.PASSWORD;
import static db.DatabaseUtil.USERNAME;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.web3signer.slashingprotection.dao.LowWatermarkDao;
import tech.pegasys.web3signer.slashingprotection.dao.SignedAttestation;
import tech.pegasys.web3signer.slashingprotection.dao.SignedAttestationsDao;
import tech.pegasys.web3signer.slashingprotection.dao.SignedBlock;
import tech.pegasys.web3signer.slashingprotection.dao.SignedBlocksDao;
import tech.pegasys.web3signer.slashingprotection.dao.SigningWatermark;
import tech.pegasys.web3signer.slashingprotection.dao.ValidatorsDao;
import tech.pegasys.web3signer.slashingprotection.interchange.InterchangeJsonProvider;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import db.DatabaseUtil;
import db.DatabaseUtil.TestDatabaseInfo;
import dsl.TestSlashingProtectionParameters;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;

public class IntegrationTestBase {

  protected final ObjectMapper mapper = new InterchangeJsonProvider().getJsonMapper();

  protected final ValidatorsDao validators = new ValidatorsDao();
  protected final LowWatermarkDao lowWatermarkDao = new LowWatermarkDao();
  protected final SignedBlocksDao signedBlocksDao = new SignedBlocksDao();
  protected final SignedAttestationsDao signedAttestationsDao = new SignedAttestationsDao();

  protected String databaseUrl;
  protected Jdbi jdbi;
  protected SlashingProtectionContext slashingProtectionContext;

  protected static final String GENESIS_VALIDATORS_ROOT =
      "0x04700007fabc8282644aed6d1c7c9e21d38a03a0c4ba193f3afe428824b3a673";
  protected static final Bytes32 GVR = Bytes32.fromHexString(GENESIS_VALIDATORS_ROOT);

  @BeforeEach
  public void setupTest() {
    final TestDatabaseInfo testDatabaseInfo = DatabaseUtil.create();
    final SlashingProtectionParameters slashingProtectionParameters =
        new TestSlashingProtectionParameters(testDatabaseInfo.databaseUrl(), USERNAME, PASSWORD);
    slashingProtectionContext =
        SlashingProtectionContextFactory.create(slashingProtectionParameters);
    jdbi = testDatabaseInfo.getJdbi();
    databaseUrl = testDatabaseInfo.databaseUrl();
    insertGvr(GVR);
  }

  protected List<SignedAttestation> findAllAttestations() {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    "SELECT validator_id, source_epoch, target_epoch, signing_root "
                        + "FROM signed_attestations")
                .mapToBean(SignedAttestation.class)
                .list());
  }

  protected Optional<SignedAttestation> findAttestationByPublicKey(final Bytes publicKey) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    "SELECT v.public_key, a.source_epoch, a.target_epoch, a.signing_root "
                        + "FROM signed_attestations AS a, validators AS v where a.validator_id = v.id "
                        + "AND v.public_key = ?")
                .bind(0, publicKey)
                .mapToBean(SignedAttestation.class)
                .findFirst());
  }

  protected List<SignedBlock> findAllBlocks() {
    return jdbi.withHandle(
        h ->
            h.createQuery("SELECT validator_id, slot, signing_root FROM signed_blocks")
                .mapToBean(SignedBlock.class)
                .list());
  }

  protected Optional<SignedBlock> findBlockByPublicKey(final Bytes publicKey) {
    return jdbi.withHandle(
        h ->
            h.createQuery(
                    "SELECT v.public_key, b.validator_id, b.slot, b.signing_root "
                        + "FROM signed_blocks AS b, validators AS v WHERE b.validator_id = v.id "
                        + "AND v.public_key = ?")
                .bind(0, publicKey)
                .mapToBean(SignedBlock.class)
                .findFirst());
  }

  protected void insertValidator(final Bytes publicKey, final int validatorId) {
    jdbi.useHandle(
        h ->
            h.execute(
                "INSERT INTO validators (id, public_key) VALUES (?, ?)", validatorId, publicKey));
  }

  protected void assertDbIsEmpty(final Jdbi jdbi) {
    jdbi.useHandle(
        h -> {
          assertThat(validators.findAllValidators(h)).isEmpty();
          assertThat(findAllAttestations()).isEmpty();
          assertThat(findAllBlocks()).isEmpty();
        });
  }

  protected void insertBlockAt(final UInt64 blockSlot, final int validatorId) {
    jdbi.useHandle(
        h ->
            signedBlocksDao.insertBlockProposal(
                h, new SignedBlock(validatorId, blockSlot, Bytes.of(100))));
  }

  protected void insertAttestationAt(
      final UInt64 sourceEpoch, final UInt64 targetEpoch, final int validatorId) {
    jdbi.useHandle(
        h ->
            signedAttestationsDao.insertAttestation(
                h, new SignedAttestation(validatorId, sourceEpoch, targetEpoch, Bytes.of(100))));
  }

  protected SigningWatermark getWatermark(final int validatorId) {
    return jdbi.withHandle(h -> lowWatermarkDao.findLowWatermarkForValidator(h, validatorId)).get();
  }

  protected List<SignedBlock> fetchBlocks(final int validatorId) {
    return jdbi.withHandle(
        h -> signedBlocksDao.findAllBlockSignedBy(h, validatorId).collect(toList()));
  }

  protected List<SignedAttestation> fetchAttestations(final int validatorId) {
    return jdbi.withHandle(
        h -> signedAttestationsDao.findAllAttestationsSignedBy(h, validatorId).collect(toList()));
  }

  protected void insertGvr(final Bytes genesisValidatorsRoot) {
    jdbi.withHandle(
        h ->
            h.execute(
                "INSERT INTO metadata (id, genesis_validators_root) VALUES (?, ?) "
                    + "ON CONFLICT (id) DO UPDATE SET genesis_validators_root = ?",
                1,
                genesisValidatorsRoot,
                genesisValidatorsRoot));
  }

  protected void insertValidatorAndCreateSlashingData(
      final RegisteredValidators registeredValidators,
      final int noOfBlocks,
      final int noOfAttestations,
      final int validatorId) {
    final Bytes validatorPublicKey = Bytes.of(validatorId);
    registeredValidators.registerValidators(List.of(validatorPublicKey));
    createSlashingData(noOfBlocks, noOfAttestations, validatorId);
  }

  protected void createSlashingData(
      final int noOfBlocks, final int noOfAttestations, final int validatorId) {
    for (int b = 0; b < noOfBlocks; b++) {
      insertBlockAt(UInt64.valueOf(b), validatorId);
    }
    for (int a = 0; a < noOfAttestations; a++) {
      insertAttestationAt(UInt64.valueOf(a), UInt64.valueOf(a), validatorId);
    }

    jdbi.useTransaction(
        h -> {
          lowWatermarkDao.updateSlotWatermarkFor(h, validatorId, UInt64.ZERO);
          lowWatermarkDao.updateEpochWatermarksFor(h, validatorId, UInt64.ZERO, UInt64.ZERO);
        });
  }
}
