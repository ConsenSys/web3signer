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

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.pegasys.web3signer.slashingprotection.interchange.model.Metadata;
import tech.pegasys.web3signer.slashingprotection.interchange.model.SignedAttestation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import com.opentable.db.postgres.embedded.EmbeddedPostgres;
import dsl.InterchangeV5Format;
import dsl.SignedArtifacts;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

public class InterchangeImportBadLogicalContentIntegrationTest
    extends InterchangeBaseIntegrationTest {

  @Test
  void attestationHasSourceGreaterThanTargetEpoch() throws IOException {
    try (final EmbeddedPostgres db = setup()) {
      final String databaseUrl =
          String.format("jdbc:postgresql://localhost:%d/postgres", db.getPort());
      final Jdbi jdbi = DbConnection.createConnection(databaseUrl, "postgres", "postgres");
      final SlashingProtection slashingProtection =
          SlashingProtectionFactory.createSlashingProtection(databaseUrl, "postgres", "postgres");

      final InterchangeV5Format interchangeData =
          new InterchangeV5Format(
              new Metadata(5, Bytes.fromHexString("0x123456")),
              List.of(
                  new SignedArtifacts(
                      "0x12345678",
                      emptyList(),
                      List.of(
                          new SignedAttestation(
                              UInt64.valueOf(6),
                              UInt64.valueOf(5),
                              Bytes.fromHexString("0x01"))))));

      final byte[] jsonInput = mapper.writeValueAsBytes(interchangeData);

      assertThatThrownBy(() -> slashingProtection.importData(new ByteArrayInputStream(jsonInput)))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("Failed to import database content");
      assertDbIsEmpty(jdbi);
    }
  }

  @Test
  void
      attemptingToImportABlockWithDifferentSigningRootToExistingEntryThrowsExceptionAndUnchangedDb() {}

  @Test
  void attemptingToImportABlockWithSameSigningRootAsExistingContinuesImport() {}

  @Test
  void attemptingToImportABlockWithANullSigningRootForSameExistingSlotContinuesImport() {}
}
