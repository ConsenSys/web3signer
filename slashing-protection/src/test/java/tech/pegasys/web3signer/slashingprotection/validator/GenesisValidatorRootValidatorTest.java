/*
 * Copyright 2022 ConsenSys AG.
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
package tech.pegasys.web3signer.slashingprotection.validator;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import tech.pegasys.web3signer.slashingprotection.dao.MetadataDao;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import db.DatabaseSetupExtension;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(DatabaseSetupExtension.class)
@ExtendWith(MockitoExtension.class)
class GenesisValidatorRootValidatorTest {
  @Spy private MetadataDao metadataDao = new MetadataDao();

  @Test
  void checkGenesisValidatorReturnTrueForNewGVR(final Jdbi jdbi) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 genesisValidatorsRoot = Bytes32.leftPad(Bytes.of(3));
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(genesisValidatorsRoot))
        .isTrue();
  }

  @Test
  void verifyCachedGVRIsUsedForNewGVR(final Jdbi jdbi) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 gvr = Bytes32.leftPad(Bytes.of(3));
    final Bytes32 otherGvr = Bytes32.leftPad(Bytes.of(4));

    // call checkGVR multiple times, only first call should interact with database.
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr)).isTrue();
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr)).isTrue();
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(otherGvr)).isFalse();

    // verify database interactions
    verify(metadataDao, times(1)).findGenesisValidatorsRoot(any());
    verify(metadataDao, times(1)).insertGenesisValidatorsRoot(any(), eq(gvr));
  }

  @Test
  void verifyCachedGVRReturnsTrueFromMultipleThreads(final Jdbi jdbi) throws InterruptedException {
    var gvrValidator = new GenesisValidatorRootValidator(jdbi, metadataDao);
    var gvr = Bytes32.leftPad(Bytes.of(3));

    var numberOfThreads = 10;
    var executorService = Executors.newFixedThreadPool(numberOfThreads);
    var allCachedGVRMatches = new AtomicBoolean(true);
    for (int i = 0; i < numberOfThreads; i++) {
      executorService.submit(
          () -> {
            boolean gvrMatches = gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr);
            if (!gvrMatches) {
              allCachedGVRMatches.set(false);
            }
          });
    }

    // Shutdown the executor service, no new tasks will be accepted
    executorService.shutdown();

    // wait for all threads to finish
    var successfulTerminate = executorService.awaitTermination(1, MINUTES);
    assertThat(successfulTerminate).isTrue();

    assertThat(allCachedGVRMatches).isTrue();
    verify(metadataDao, times(1)).findGenesisValidatorsRoot(any());
    verify(metadataDao, times(1)).insertGenesisValidatorsRoot(any(), any());
  }

  @Test
  void verifyCachedGVRIsUsedForExistingGVR(final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 gvr = Bytes32.leftPad(Bytes.of(3));
    final Bytes32 otherGvr = Bytes32.leftPad(Bytes.of(4));

    insertGvr(handle, gvr);

    // call checkGVR multiple times, only first call should interact with database.
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr)).isTrue();
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr)).isTrue();
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(otherGvr)).isFalse();

    // verify database interactions
    verify(metadataDao, times(1)).findGenesisValidatorsRoot(any());
    verify(metadataDao, never()).insertGenesisValidatorsRoot(any(), any());
  }

  @Test
  void verifyCachedGVRIsNotInitializedWhenDBExceptionHappens(final Jdbi jdbi) {
    // set up exception to be thrown in first call and call real method in second call.
    doThrow(new RuntimeException("DB Not Available"))
        .doCallRealMethod()
        .when(metadataDao)
        .findGenesisValidatorsRoot(any());

    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 gvr = Bytes32.leftPad(Bytes.of(3));
    final Bytes32 otherGvr = Bytes32.leftPad(Bytes.of(4));

    // first call should throw an exception.
    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(() -> gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr));

    // second call should call find (which returns empty result) and insert the supplied gvr.
    // Also cache the supplied gvr in-memory.
    gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr);

    // subsequent calls should not interact with the database
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr)).isTrue();
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(otherGvr)).isFalse();

    // verify interactions
    verify(metadataDao, times(2)).findGenesisValidatorsRoot(any());
    verify(metadataDao, times(1)).insertGenesisValidatorsRoot(any(), any());
  }

  @Test
  void checkGenesisValidatorReturnsTrueForExistingGVR(final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    Bytes32 genesisValidatorsRoot = Bytes32.leftPad(Bytes.of(3));
    insertGvr(handle, genesisValidatorsRoot);
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(genesisValidatorsRoot))
        .isTrue();
  }

  @Test
  void checkGenesisValidatorReturnsFalseForDifferentGVR(final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    Bytes32 genesisValidatorsRoot = Bytes32.leftPad(Bytes.of(3));
    insertGvr(handle, genesisValidatorsRoot);
    assertThat(
            gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(Bytes32.leftPad(Bytes.of(4))))
        .isFalse();
  }

  @Test
  void genesisValidatorRootExistsReturnsFalseWhenMetadataIsEmpty(final Jdbi jdbi) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    assertThat(gvrValidator.genesisValidatorRootExists()).isFalse();
  }

  @Test
  void genesisValidatorRootExistsReturnsTrueWhenMetadataIsNotEmpty(
      final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    insertGvr(handle, Bytes32.leftPad(Bytes.of(3)));
    assertThat(gvrValidator.genesisValidatorRootExists()).isTrue();
  }

  @Test
  void initialiseGenesisValidatorsRootStoresGvrInEmptyDatabase(
      final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 gvr = Bytes32.leftPad(Bytes.of(3));
    final Bytes32 otherGvr = Bytes32.leftPad(Bytes.of(4));

    gvrValidator.initialiseGenesisValidatorsRoot(gvr);

    assertThat(
            handle
                .createQuery("SELECT genesis_validators_root FROM metadata WHERE id = ?")
                .bind(0, 1)
                .mapTo(Bytes32.class)
                .findFirst())
        .contains(gvr);

    // the cache is primed, so subsequent checks do not interact with the database
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(gvr)).isTrue();
    assertThat(gvrValidator.checkGenesisValidatorsRootAndInsertIfEmpty(otherGvr)).isFalse();

    verify(metadataDao, times(1)).findGenesisValidatorsRoot(any());
    verify(metadataDao, times(1)).insertGenesisValidatorsRoot(any(), eq(gvr));
  }

  @Test
  void initialiseGenesisValidatorsRootThrowsWhenDatabaseHoldsDifferentGvr(
      final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 gvr = Bytes32.leftPad(Bytes.of(3));
    final Bytes32 otherGvr = Bytes32.leftPad(Bytes.of(4));
    insertGvr(handle, gvr);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> gvrValidator.initialiseGenesisValidatorsRoot(otherGvr))
        .withMessageContaining(otherGvr.toHexString())
        .withMessageContaining(gvr.toHexString());

    verify(metadataDao, never()).insertGenesisValidatorsRoot(any(), any());
  }

  @Test
  void initialiseGenesisValidatorsRootSucceedsWhenDatabaseHoldsSameGvr(
      final Jdbi jdbi, final Handle handle) {
    final GenesisValidatorRootValidator gvrValidator =
        new GenesisValidatorRootValidator(jdbi, metadataDao);
    final Bytes32 gvr = Bytes32.leftPad(Bytes.of(3));
    insertGvr(handle, gvr);

    gvrValidator.initialiseGenesisValidatorsRoot(gvr);

    verify(metadataDao, never()).insertGenesisValidatorsRoot(any(), any());
  }

  private void insertGvr(final Handle handle, final Bytes genesisValidatorsRoot) {
    handle.execute(
        "INSERT INTO metadata (id, genesis_validators_root) VALUES (?, ?)",
        1,
        genesisValidatorsRoot);
  }
}
