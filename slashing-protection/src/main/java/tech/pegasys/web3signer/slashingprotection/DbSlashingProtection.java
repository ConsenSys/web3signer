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

import static org.jdbi.v3.core.transaction.TransactionIsolationLevel.READ_COMMITTED;
import static tech.pegasys.web3signer.slashingprotection.DbLocker.lockForValidator;

import tech.pegasys.web3signer.slashingprotection.DbLocker.LockType;
import tech.pegasys.web3signer.slashingprotection.dao.HighWatermark;
import tech.pegasys.web3signer.slashingprotection.dao.LowWatermarkDao;
import tech.pegasys.web3signer.slashingprotection.dao.MetadataDao;
import tech.pegasys.web3signer.slashingprotection.dao.SignedAttestationsDao;
import tech.pegasys.web3signer.slashingprotection.dao.SignedBlocksDao;
import tech.pegasys.web3signer.slashingprotection.dao.ValidatorsDao;
import tech.pegasys.web3signer.slashingprotection.interchange.EmptyDataIncrementalInterchangeV5Exporter;
import tech.pegasys.web3signer.slashingprotection.interchange.IncrementalExporter;
import tech.pegasys.web3signer.slashingprotection.interchange.InterchangeManager;
import tech.pegasys.web3signer.slashingprotection.interchange.InterchangeV5Manager;
import tech.pegasys.web3signer.slashingprotection.validator.AttestationValidator;
import tech.pegasys.web3signer.slashingprotection.validator.BlockValidator;
import tech.pegasys.web3signer.slashingprotection.validator.GenesisValidatorRootValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

public class DbSlashingProtection implements SlashingProtection {
  private static final Logger LOG = LogManager.getLogger();

  private final Jdbi jdbi;
  private final ValidatorsDao validatorsDao;
  private final SignedBlocksDao signedBlocksDao;
  private final SignedAttestationsDao signedAttestationsDao;
  private final InterchangeManager interchangeManager;
  private final LowWatermarkDao lowWatermarkDao;
  private final MetadataDao metadataDao;
  private final GenesisValidatorRootValidator gvrValidator;
  private final RegisteredValidators registeredValidators;

  public DbSlashingProtection(
      final Jdbi jdbi,
      final ValidatorsDao validatorsDao,
      final SignedBlocksDao signedBlocksDao,
      final SignedAttestationsDao signedAttestationsDao,
      final MetadataDao metadataDao,
      final LowWatermarkDao lowWatermarkDao,
      final RegisteredValidators registeredValidators) {
    this.jdbi = jdbi;
    this.validatorsDao = validatorsDao;
    this.signedBlocksDao = signedBlocksDao;
    this.signedAttestationsDao = signedAttestationsDao;
    this.lowWatermarkDao = lowWatermarkDao;
    this.metadataDao = metadataDao;
    this.registeredValidators = registeredValidators;
    this.gvrValidator = new GenesisValidatorRootValidator(jdbi, metadataDao);
    this.interchangeManager =
        new InterchangeV5Manager(
            jdbi,
            validatorsDao,
            signedBlocksDao,
            signedAttestationsDao,
            metadataDao,
            lowWatermarkDao);
  }

  @Override
  public void importData(final InputStream input) {
    try {
      LOG.info("Importing slashing protection database");
      interchangeManager.importData(input);
      LOG.info("Import complete");
    } catch (final IOException | UnsupportedOperationException | IllegalArgumentException e) {
      throw new RuntimeException("Failed to import database content", e);
    }
  }

  @Override
  public void importDataWithFilter(final InputStream input, final List<String> pubkeys) {
    try {
      LOG.info("Importing slashing protection database for keys: " + String.join(",", pubkeys));
      interchangeManager.importDataWithFilter(input, pubkeys);
      LOG.info("Import complete");
    } catch (final IOException | UnsupportedOperationException | IllegalArgumentException e) {
      throw new RuntimeException("Failed to import database content", e);
    }
  }

  @Override
  public void exportData(final OutputStream output) {
    try {
      LOG.info("Exporting slashing protection database");
      interchangeManager.exportData(output);
      LOG.info("Export complete");
    } catch (IOException e) {
      throw new RuntimeException("Failed to export database content", e);
    }
  }

  @Override
  public void exportDataWithFilter(final OutputStream output, final List<String> pubkeys) {
    try {
      LOG.info("Exporting slashing protection database for keys: " + String.join(",", pubkeys));
      interchangeManager.exportDataWithFilter(output, pubkeys);
      LOG.info("Export complete");
    } catch (IOException e) {
      throw new RuntimeException("Failed to export database content", e);
    }
  }

  @Override
  public IncrementalExporter createIncrementalExporter(final OutputStream out) {
    // when GVR is empty, there is no slashing data to export, hence return a No-Op exporter that
    // can nicely close OutputStream.
    if (!gvrValidator.genesisValidatorRootExists()) {
      return new EmptyDataIncrementalInterchangeV5Exporter(out);
    }

    try {
      return interchangeManager.createIncrementalExporter(out);
    } catch (final IOException e) {
      throw new RuntimeException(
          "Failed to initialise incremental exporter for slashing protection data", e);
    }
  }

  @Override
  public boolean maySignAttestation(
      final Bytes publicKey,
      final Bytes signingRoot,
      final UInt64 sourceEpoch,
      final UInt64 targetEpoch,
      final Bytes32 genesisValidatorsRoot) {
    final int validatorId = registeredValidators.mustGetValidatorIdForPublicKey(publicKey);

    if (!gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(genesisValidatorsRoot)) {
      return false;
    }

    return jdbi.inTransaction(
        READ_COMMITTED,
        handle -> {
          lockForValidator(handle, LockType.ATTESTATION, validatorId);

          if (!isEnabled(handle, validatorId)) {
            LOG.warn(
                "Signing attempted for disabled validator {}. To sign with this validator"
                    + " you must import the validator keystore using the key manager import API",
                publicKey);
            return false;
          }

          final AttestationValidator attestationValidator =
              new AttestationValidator(
                  handle,
                  publicKey,
                  signingRoot,
                  sourceEpoch,
                  targetEpoch,
                  validatorId,
                  signedAttestationsDao,
                  lowWatermarkDao,
                  metadataDao);

          if (attestationValidator.sourceGreaterThanTargetEpoch()) {
            return false;
          }

          if (attestationValidator.hasEpochAtOrBeyondHighWatermark()
              || attestationValidator.hasSourceOlderThanWatermark()
              || attestationValidator.hasTargetOlderThanWatermark()
              || attestationValidator.directlyConflictsWithExistingEntry()
              || attestationValidator.isSurroundedByExistingAttestation()
              || attestationValidator.surroundsExistingAttestation()) {
            return false;
          }
          if (!attestationValidator.alreadyExists()) {
            attestationValidator.persist();
          }
          return true;
        });
  }

  @Override
  public boolean maySignBlock(
      final Bytes publicKey,
      final Bytes signingRoot,
      final UInt64 blockSlot,
      final Bytes32 genesisValidatorsRoot) {
    final int validatorId = registeredValidators.mustGetValidatorIdForPublicKey(publicKey);
    if (!gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(genesisValidatorsRoot)) {
      return false;
    }
    return jdbi.inTransaction(
        READ_COMMITTED,
        h -> {
          lockForValidator(h, LockType.BLOCK, validatorId);

          if (!isEnabled(h, validatorId)) {
            LOG.warn(
                "Signing attempted for disabled validator {}. To sign with this validator"
                    + " you must import the validator keystore using the key manager import API",
                publicKey);
            return false;
          }

          final BlockValidator blockValidator =
              new BlockValidator(
                  h,
                  signingRoot,
                  blockSlot,
                  validatorId,
                  signedBlocksDao,
                  lowWatermarkDao,
                  metadataDao);

          if (blockValidator.isAtOrBeyondHighWatermark()
              || blockValidator.isOlderThanWatermark()
              || blockValidator.directlyConflictsWithExistingEntry()) {
            return false;
          }
          if (!blockValidator.alreadyExists()) {
            blockValidator.persist();
          }
          return true;
        });
  }

  @Override
  public boolean hasSlashingProtectionDataFor(final Bytes publicKey) {
    final Optional<Integer> maybeValidatorId =
        registeredValidators.getValidatorIdForPublicKey(publicKey);
    return maybeValidatorId
        .map(
            validatorId ->
                jdbi.inTransaction(
                    READ_COMMITTED, handle -> validatorsDao.hasSigned(handle, validatorId)))
        .orElse(false);
  }

  @Override
  public void initialiseGenesisValidatorsRoot(final Bytes32 genesisValidatorsRoot) {
    gvrValidator.initialiseGenesisValidatorsRoot(genesisValidatorsRoot);
  }

  @Override
  public boolean hasGenesisValidatorsRoot() {
    return gvrValidator.genesisValidatorRootExists();
  }

  @Override
  public boolean isEnabledValidator(final Bytes publicKey) {
    final int validatorId = registeredValidators.mustGetValidatorIdForPublicKey(publicKey);
    return jdbi.inTransaction(handle -> isEnabled(handle, validatorId));
  }

  @Override
  public void updateValidatorEnabledStatus(final Bytes publicKey, final boolean enabled) {
    final int validatorId = registeredValidators.mustGetValidatorIdForPublicKey(publicKey);
    jdbi.useTransaction(
        READ_COMMITTED,
        handle -> {
          lockForValidator(handle, LockType.ATTESTATION, validatorId);
          lockForValidator(handle, LockType.BLOCK, validatorId);
          validatorsDao.setEnabled(handle, validatorId, enabled);
        });
  }

  @Override
  public Optional<HighWatermark> getHighWatermark() {
    return jdbi.inTransaction(READ_COMMITTED, metadataDao::findHighWatermark);
  }

  private boolean isEnabled(final Handle handle, final int validatorId) {
    return validatorsDao.isEnabled(handle, validatorId);
  }
}
