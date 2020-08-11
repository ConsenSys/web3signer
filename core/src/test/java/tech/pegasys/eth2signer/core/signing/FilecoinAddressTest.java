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
package tech.pegasys.eth2signer.core.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.pegasys.eth2signer.core.signing.FilecoinAddress.Network;
import tech.pegasys.eth2signer.core.signing.FilecoinAddress.Protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class FilecoinAddressTest {

  @ParameterizedTest
  @CsvFileSource(resources = "bls_testvectors.csv")
  void testVectorsForBlsAddresses(final String address, final String payload) {
    verifyAddress(address, payload, Protocol.BLS);
  }

  @ParameterizedTest
  @CsvFileSource(resources = "secp_testvectors.csv")
  void testVectorsForSecpAddresses(final String address, final String payload) {
    verifyAddress(address, payload, Protocol.SECP256K1);
  }

  @ParameterizedTest
  @CsvFileSource(resources = "id_testvectors.csv")
  void testVectorsForIdAddresses(final String address, final String payload) {
    verifyAddress(address, payload, Protocol.ID);
  }

  @ParameterizedTest
  @CsvFileSource(resources = "actor_testvectors.csv")
  void testVectorsForActorAddresses(final String address, final String payload) {
    verifyAddress(address, payload, Protocol.ACTOR);
  }

  @Test
  void addressWithInvalidChecksumThrowsError() {
    assertThatThrownBy(() -> FilecoinAddress.decode("f17uoq6tp427uzv7fztkbsnn64iwotfrristwprz"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Filecoin address checksum doesn't match");
  }

  @Test
  void addressWithInvalidNetworkThrowsError() {
    assertThatThrownBy(() -> FilecoinAddress.decode("Q17uoq6tp427uzv7fztkbsnn64iwotfrristwpryy"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unknown Filecoin network");
  }

  @Test
  void addressWithInvalidProtocolThrowsError() {
    assertThatThrownBy(() -> FilecoinAddress.decode("f57uoq6tp427uzv7fztkbsnn64iwotfrristwpryy"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unknown Filecoin protocol");
  }

  @Test
  void addressWithInvalidPayloadThrowsError() {
    assertThatThrownBy(() -> FilecoinAddress.decode("f17uoq6tp427uzT7fztkbsnn64iwot!rristwpryy"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid payload must be base32 encoded");
  }

  private void verifyAddress(final String address, final String payload, final Protocol bls) {
    final FilecoinAddress filecoinAddress = FilecoinAddress.fromString(address);
    final String expectedPayload = payload.substring(2);
    assertThat(filecoinAddress.getPayload().toUnprefixedHexString()).isEqualTo(expectedPayload);
    assertThat(filecoinAddress.getProtocol()).isEqualTo(bls);

    final String encodedAddress = filecoinAddress.encode(Network.MAINNET);
    assertThat(encodedAddress).isEqualTo(address);
  }
}
