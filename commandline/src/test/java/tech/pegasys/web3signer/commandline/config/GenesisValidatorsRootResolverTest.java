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
package tech.pegasys.web3signer.commandline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import tech.pegasys.teku.networks.Eth2NetworkConfiguration;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.networks.Eth2Network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GenesisValidatorsRootResolverTest {

  @ParameterizedTest
  @EnumSource(
      value = Eth2Network.class,
      names = {"MAINNET", "GNOSIS", "CHIADO", "LUKSO"})
  void knownNetworksMatchTheGenesisValidatorsRootOfTekusBundledGenesisState(
      final Eth2Network network) {
    final Eth2NetworkConfiguration config = Eth2NetworkConfiguration.builder(network).build();
    final String source = config.getNetworkBoostrapConfig().getGenesisState().orElseThrow();

    assertThat(
            GenesisValidatorsRootResolver.resolve(
                network.configName(), Optional.of(source), config.getSpec()))
        .contains(GenesisValidatorsRootResolver.KNOWN_GENESIS_VALIDATORS_ROOTS.get(network));
  }

  @Test
  void genesisStateOverridesTheBuiltInRootOfAKnownNetwork() {
    final Eth2NetworkConfiguration gnosisConfig =
        Eth2NetworkConfiguration.builder(Eth2Network.GNOSIS).build();
    final String gnosisSource =
        gnosisConfig.getNetworkBoostrapConfig().getGenesisState().orElseThrow();

    assertThat(
            GenesisValidatorsRootResolver.resolve(
                Eth2Network.MAINNET.configName(),
                Optional.of(gnosisSource),
                gnosisConfig.getSpec()))
        .contains(
            GenesisValidatorsRootResolver.KNOWN_GENESIS_VALIDATORS_ROOTS.get(Eth2Network.GNOSIS));
  }

  @Test
  void resolveReturnsEmptyForANetworkWithoutAKnownRoot() {
    final Spec minimalSpec =
        Eth2NetworkConfiguration.builder(Eth2Network.MINIMAL).build().getSpec();

    assertThat(GenesisValidatorsRootResolver.resolve("minimal", Optional.empty(), minimalSpec))
        .isEmpty();
  }

  @Test
  void resolveReturnsEmptyForACustomNetworkConfigFile() {
    final Spec mainnetSpec =
        Eth2NetworkConfiguration.builder(Eth2Network.MAINNET).build().getSpec();

    assertThat(
            GenesisValidatorsRootResolver.resolve(
                "/some/custom.yaml", Optional.empty(), mainnetSpec))
        .isEmpty();
  }

  @Test
  void resolveThrowsWhenTheGenesisStateFileDoesNotExist() {
    final Spec mainnetSpec =
        Eth2NetworkConfiguration.builder(Eth2Network.MAINNET).build().getSpec();

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                GenesisValidatorsRootResolver.resolve(
                    "minimal", Optional.of("/nonexistent/state.ssz"), mainnetSpec))
        .withMessageContaining("/nonexistent/state.ssz");
  }

  @Test
  void resolveThrowsWhenTheGenesisStateIsNotABeaconState(@TempDir final Path tempDir)
      throws IOException {
    final Spec mainnetSpec =
        Eth2NetworkConfiguration.builder(Eth2Network.MAINNET).build().getSpec();
    final Path stateFile = tempDir.resolve("state.ssz");
    Files.write(stateFile, new byte[10]);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                GenesisValidatorsRootResolver.resolve(
                    "minimal", Optional.of(stateFile.toString()), mainnetSpec))
        .withMessageContaining(stateFile.toString());
  }
}
