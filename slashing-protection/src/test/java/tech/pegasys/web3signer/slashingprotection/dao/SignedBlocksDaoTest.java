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

import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.web3signer.slashingprotection.ArgumentFactories.BytesArgumentFactory;
import tech.pegasys.web3signer.slashingprotection.ArgumentFactories.UInt64ArgumentFactory;
import tech.pegasys.web3signer.slashingprotection.ColumnMappers.BytesColumnMapper;
import tech.pegasys.web3signer.slashingprotection.ColumnMappers.UInt64ColumnMapper;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.argument.Arguments;
import org.jdbi.v3.core.mapper.ColumnMappers;
import org.jdbi.v3.testing.JdbiRule;
import org.jdbi.v3.testing.Migration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SignedBlocksDaoTest {

  @Rule
  public JdbiRule postgres =
      JdbiRule.embeddedPostgres()
          .withMigration(Migration.before().withPath("migrations/postgresql"));

  private Handle handle;

  @Before
  public void setup() {
    postgres.getJdbi().getConfig(Arguments.class).register(new BytesArgumentFactory());
    postgres.getJdbi().getConfig(Arguments.class).register(new UInt64ArgumentFactory());
    postgres.getJdbi().getConfig(ColumnMappers.class).register(new BytesColumnMapper());
    postgres.getJdbi().getConfig(ColumnMappers.class).register(new UInt64ColumnMapper());
    handle = postgres.getJdbi().open();
  }

  @After
  public void cleanup() {
    handle.close();
  }

  @Test
  public void findsExistingBlockInDb() {
    insertBlock(handle, Bytes.of(100), 1, 2, Bytes.of(3));
    final SignedBlocksDao signedBlocksDao = new SignedBlocksDao(handle);

    final Optional<SignedBlock> existingBlock =
        signedBlocksDao.findExistingBlock(1, UInt64.valueOf(2));
    assertThat(existingBlock).isNotEmpty();
    assertThat(existingBlock.get().getValidatorId()).isEqualTo(1);
    assertThat(existingBlock.get().getSlot()).isEqualTo(2);
    assertThat(existingBlock.get().getSigningRoot()).isEqualTo(Bytes.of(3));
  }

  @Test
  public void returnsEmptyForNonExistingBlockInDb() {
    insertBlock(handle, Bytes.of(100), 1, 2, Bytes.of(3));
    final SignedBlocksDao signedBlocksDao = new SignedBlocksDao(handle);
    assertThat(signedBlocksDao.findExistingBlock(1, UInt64.valueOf(1))).isEmpty();
    assertThat(signedBlocksDao.findExistingBlock(2, UInt64.valueOf(2))).isEmpty();
  }

  @Test
  public void storesBlockInDb() {
    final SignedBlocksDao signedBlocksDao = new SignedBlocksDao(handle);
    final ValidatorsDao validatorsDao = new ValidatorsDao(handle);

    validatorsDao.registerValidators(List.of(Bytes.of(100)));
    validatorsDao.registerValidators(List.of(Bytes.of(101)));
    validatorsDao.registerValidators(List.of(Bytes.of(102)));
    signedBlocksDao.insertBlockProposal(1, UInt64.valueOf(2), Bytes.of(100));
    signedBlocksDao.insertBlockProposal(2, UInt64.MAX_VALUE, Bytes.of(101));
    signedBlocksDao.insertBlockProposal(3, UInt64.MIN_VALUE, Bytes.of(102));

    final List<SignedBlock> validators =
        handle
            .createQuery("SELECT * FROM signed_blocks ORDER BY validator_id")
            .mapToBean(SignedBlock.class)
            .list();
    assertThat(validators.size()).isEqualTo(3);
    assertThat(validators.get(0))
        .isEqualToComparingFieldByField(new SignedBlock(1, UInt64.valueOf(2), Bytes.of(100)));
    assertThat(validators.get(1))
        .isEqualToComparingFieldByField(new SignedBlock(2, UInt64.MAX_VALUE, Bytes.of(101)));
    assertThat(validators.get(2))
        .isEqualToComparingFieldByField(new SignedBlock(3, UInt64.MIN_VALUE, Bytes.of(102)));
  }

  private void insertBlock(
      final Handle h,
      final Bytes publicKey,
      final int validatorId,
      final int slot,
      final Bytes signingRoot) {
    h.execute("INSERT INTO validators (id, public_key) VALUES (?, ?)", validatorId, publicKey);
    h.execute(
        "INSERT INTO signed_blocks (validator_id, slot, signing_root) VALUES (?, ?, ?)",
        validatorId,
        slot,
        signingRoot);
  }
}
