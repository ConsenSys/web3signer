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
package tech.pegasys.web3signer.slashingprotection.dao;

import static db.DatabaseUtil.MIGRATIONS_LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.web3signer.slashingprotection.dao.DatabaseVersionDao.VALIDATOR_ENABLE_FLAG_VERSION;

import java.util.ArrayList;
import java.util.List;

import db.DatabaseSetupExtension;
import db.DatabaseUtil;
import db.DatabaseUtil.TestDatabaseInfo;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseSetupExtension.class)
public class ValidatorsDaoTest {

  @Test
  public void retrievesSpecifiedValidatorsFromDb(final Handle handle) {
    insertValidator(handle, Bytes.of(100));
    insertValidator(handle, Bytes.of(101));
    insertValidator(handle, Bytes.of(102));

    final ValidatorsDao validatorsDao = new ValidatorsDao();
    final List<Validator> registeredValidators =
        validatorsDao.retrieveValidators(handle, List.of(Bytes.of(101), Bytes.of(102)));
    assertThat(registeredValidators).hasSize(2);
    assertThat(registeredValidators.get(0))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(2, Bytes.of(101)));
    assertThat(registeredValidators.get(1))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(3, Bytes.of(102)));
  }

  @Test
  public void storesValidatorsInDb(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    final List<Validator> validators =
        validatorsDao.registerValidators(handle, List.of(Bytes.of(101), Bytes.of(102)));

    assertThat(validators.size()).isEqualTo(2);
    assertThat(validators.get(0))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(1, Bytes.of(101)));
    assertThat(validators.get(1))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(2, Bytes.of(102)));
  }

  @Test
  public void storesUnregisteredValidatorsInDb(final Handle handle) {
    insertValidator(handle, Bytes.of(100));
    insertValidator(handle, Bytes.of(101));
    insertValidator(handle, Bytes.of(102));

    final ValidatorsDao validatorsDao = new ValidatorsDao();
    final List<Bytes> validators1 =
        List.of(Bytes.of(101), Bytes.of(102), Bytes.of(103), Bytes.of(104));
    final List<Validator> registeredValidators =
        validatorsDao.retrieveValidators(handle, validators1);
    final List<Bytes> validatorsMissingFromDb = new ArrayList<>(validators1);
    registeredValidators.forEach(v -> validatorsMissingFromDb.remove(v.getPublicKey()));
    validatorsDao.registerValidators(handle, validatorsMissingFromDb);

    final List<Validator> validators =
        handle
            .createQuery("SELECT * FROM validators ORDER BY ID")
            .mapToBean(Validator.class)
            .list();
    assertThat(validators.size()).isEqualTo(5);
    assertThat(validators.get(0))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(1, Bytes.of(100)));
    assertThat(validators.get(1))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(2, Bytes.of(101)));
    assertThat(validators.get(2))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(3, Bytes.of(102)));
    assertThat(validators.get(3))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(4, Bytes.of(103)));
    assertThat(validators.get(4))
        .usingRecursiveComparison()
        .isEqualTo(new Validator(5, Bytes.of(104)));
  }

  @Test
  public void isEnabledReturnsTrueForEnabledValidator(final Handle handle) {
    insertValidator(handle, Bytes.of(1), true);
    assertThat(new ValidatorsDao().isEnabled(handle, 1)).isTrue();
  }

  @Test
  public void isEnabledReturnsTrueForExistingValidator() {
    final TestDatabaseInfo testDatabaseInfo = DatabaseUtil.createWithoutMigration();
    final Jdbi jdbi = testDatabaseInfo.getJdbi();

    try (Handle handle = jdbi.open()) {
      final String versionBeforeEnableFlag = String.valueOf(VALIDATOR_ENABLE_FLAG_VERSION - 1);
      final Flyway flywayBeforeValidatorEnableFlag =
          Flyway.configure()
              .locations(MIGRATIONS_LOCATION)
              .dataSource(testDatabaseInfo.getDataSource())
              .target(versionBeforeEnableFlag)
              .load();
      flywayBeforeValidatorEnableFlag.migrate();

      handle.execute("INSERT INTO validators (public_key) VALUES (?)", Bytes.of(100));

      final Flyway flywayLatest =
          Flyway.configure()
              .locations(MIGRATIONS_LOCATION)
              .dataSource(testDatabaseInfo.getDataSource())
              .load();
      flywayLatest.migrate();
      assertThat(new ValidatorsDao().isEnabled(handle, 1)).isTrue();
    }
  }

  @Test
  public void isEnabledReturnsFalseForDisabledValidator(final Handle handle) {
    insertValidator(handle, Bytes.of(1), false);
    assertThat(new ValidatorsDao().isEnabled(handle, 1)).isFalse();
  }

  @Test
  public void isEnabledReturnsFalseForNonExistingValidator(final Handle handle) {
    assertThat(new ValidatorsDao().isEnabled(handle, 1)).isFalse();
  }

  @Test
  public void canEnableAlreadyDisabledValidator(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    insertValidator(handle, Bytes.of(1), false);

    handle.useTransaction(h -> validatorsDao.setEnabled(h, 1, true));
    assertThat(validatorsDao.isEnabled(handle, 1)).isTrue();
  }

  @Test
  public void canDisableDefaultEnabledValidator(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    insertValidator(handle, Bytes.of(1));

    handle.useTransaction(h -> validatorsDao.setEnabled(h, 1, false));
    assertThat(validatorsDao.isEnabled(handle, 1)).isFalse();
  }

  @Test
  public void canDisableEnabledValidator(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    insertValidator(handle, Bytes.of(1), true);

    handle.useTransaction(h -> validatorsDao.setEnabled(h, 1, false));
    assertThat(validatorsDao.isEnabled(handle, 1)).isFalse();
  }

  @Test
  public void setEnabledBatchIsNoOpForEmptyList(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    insertValidator(handle, Bytes.of(1), true);

    handle.useTransaction(h -> validatorsDao.setEnabledBatch(h, List.of(), false));
    assertThat(validatorsDao.isEnabled(handle, 1)).isTrue();
  }

  @Test
  public void setEnabledBatchDisablesMultipleValidators(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    insertValidator(handle, Bytes.of(1), true);
    insertValidator(handle, Bytes.of(2), true);
    insertValidator(handle, Bytes.of(3), true);

    handle.useTransaction(h -> validatorsDao.setEnabledBatch(h, List.of(1, 2, 3), false));

    assertThat(validatorsDao.isEnabled(handle, 1)).isFalse();
    assertThat(validatorsDao.isEnabled(handle, 2)).isFalse();
    assertThat(validatorsDao.isEnabled(handle, 3)).isFalse();
  }

  @Test
  public void setEnabledBatchEnablesMultipleValidators(final Handle handle) {
    final ValidatorsDao validatorsDao = new ValidatorsDao();
    insertValidator(handle, Bytes.of(1), false);
    insertValidator(handle, Bytes.of(2), false);
    insertValidator(handle, Bytes.of(3), false);

    handle.useTransaction(h -> validatorsDao.setEnabledBatch(h, List.of(1, 2, 3), true));

    assertThat(validatorsDao.isEnabled(handle, 1)).isTrue();
    assertThat(validatorsDao.isEnabled(handle, 2)).isTrue();
    assertThat(validatorsDao.isEnabled(handle, 3)).isTrue();
  }

  @Test
  public void hasSignedReturnsFalseWhenNoSignedBlocksOrAttestations(final Handle handle) {
    insertValidator(handle, 1, Bytes.of(9));
    assertThat(new ValidatorsDao().hasSigned(handle, 1)).isFalse();
  }

  @Test
  public void hasSignedReturnsTrueWhenSignedBlock(final Handle handle) {
    insertValidator(handle, 1, Bytes.of(9));
    insertBlock(handle, 1);
    assertThat(new ValidatorsDao().hasSigned(handle, 1)).isTrue();
  }

  @Test
  public void hasSignedReturnsTrueWhenSignedAttestation(final Handle handle) {
    insertValidator(handle, 1, Bytes.of(9));
    insertAttestation(handle, 1);
    assertThat(new ValidatorsDao().hasSigned(handle, 1)).isTrue();
  }

  private void insertValidator(final Handle h, final Bytes publicKey) {
    insertValidator(h, publicKey, true);
  }

  private void insertValidator(final Handle h, final Bytes publicKey, final boolean enabled) {
    h.execute("INSERT INTO validators (public_key, enabled) VALUES (?, ?)", publicKey, enabled);
  }

  private void insertValidator(final Handle h, final int validatorId, final Bytes publicKey) {
    h.execute("INSERT INTO validators (id, public_key) VALUES (?, ?)", validatorId, publicKey);
  }

  private void insertBlock(final Handle handle, final int validatorId) {
    handle.execute(
        "INSERT INTO signed_blocks (validator_id, slot, signing_root) VALUES (?, ?, ?)",
        validatorId,
        2,
        Bytes.of(3));
  }

  private void insertAttestation(final Handle handle, final int validatorId) {
    handle.execute(
        "INSERT INTO signed_attestations "
            + "(validator_id, signing_root, source_epoch, target_epoch) "
            + "VALUES (?, ?, ?, ?)",
        validatorId,
        Bytes.of(2),
        UInt64.valueOf(3),
        UInt64.valueOf(4));
  }
}
