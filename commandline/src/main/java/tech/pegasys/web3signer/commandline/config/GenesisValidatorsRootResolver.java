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

import tech.pegasys.teku.infrastructure.io.resource.ResourceLoader;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.networks.Eth2Network;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public final class GenesisValidatorsRootResolver {

  private static final Logger LOG = LogManager.getLogger();

  // Genesis validators roots read from the genesis SSZ files used by Teku 26.9.1: the four
  // bundled in the networks jar (mainnet, gnosis, chiado, lukso), and Teku's pinned eth-clients
  // URLs for sepolia, holesky and hoodi. EPHEMERY and PLATABERGET are left out because their
  // genesis rotates, and MINIMAL, SWIFT and LESS_SWIFT have no fixed genesis.
  static final Map<Eth2Network, Bytes32> KNOWN_GENESIS_VALIDATORS_ROOTS =
      Map.of(
          Eth2Network.MAINNET,
          Bytes32.fromHexString(
              "0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95"),
          Eth2Network.SEPOLIA,
          Bytes32.fromHexString(
              "0xd8ea171f3c94aea21ebc42a1ed61052acf3f9209c00e4efbaaddac09ed9b8078"),
          Eth2Network.HOLESKY,
          Bytes32.fromHexString(
              "0x9143aa7c615a7f7115e2b6aac319c03529df8242ae705fba9df39b79c59fa8b1"),
          Eth2Network.HOODI,
          Bytes32.fromHexString(
              "0x212f13fc4df078b6cb7db228f1c8307566dcecf900867401a92023d7ba99cb5f"),
          Eth2Network.GNOSIS,
          Bytes32.fromHexString(
              "0xf5dcb5564e829aab27264b9becd5dfaa017085611224cb3036f573368dbb9d47"),
          Eth2Network.CHIADO,
          Bytes32.fromHexString(
              "0x9d642dac73058fbf39c0ae41ab1e34e4d889043cb199851ded7095bc99eb4c1e"),
          Eth2Network.LUKSO,
          Bytes32.fromHexString(
              "0xa27edd68cde5c396f499157945d062a010308ce5ed5719a6b1e12ad2a51b97e6"));

  private GenesisValidatorsRootResolver() {}

  /**
   * Determines the genesis validators root of the configured network. A beacon state, when given,
   * always takes precedence over the built-in constants of known networks.
   *
   * @param network the value of the {@code --network} option
   * @param genesisState the value of the {@code --genesis-state} option, if any
   * @param spec the spec of the configured network
   * @return the genesis validators root, or empty when it cannot be determined
   * @throws IllegalArgumentException if the beacon state cannot be read
   */
  public static Optional<Bytes32> resolve(
      final String network, final Optional<String> genesisState, final Spec spec) {
    if (genesisState.isPresent()) {
      final String source = genesisState.get();
      final Bytes32 genesisValidatorsRoot = loadFromBeaconState(source, spec);
      LOG.info(
          "Genesis validators root {} read from beacon state {}", genesisValidatorsRoot, source);
      return Optional.of(genesisValidatorsRoot);
    }

    final Optional<Bytes32> knownRoot =
        Eth2Network.fromStringLenient(network).map(KNOWN_GENESIS_VALIDATORS_ROOTS::get);
    knownRoot.ifPresent(
        root -> LOG.info("Genesis validators root {} for network {}", root, network));
    return knownRoot;
  }

  static Bytes32 loadFromBeaconState(final String source, final Spec spec) {
    try {
      final Bytes state =
          ResourceLoader.urlOrFile("application/octet-stream")
              .loadBytes(source)
              .orElseThrow(() -> new IllegalArgumentException("resource not found"));
      return spec.deserializeBeaconState(state).getGenesisValidatorsRoot();
    } catch (final IOException | RuntimeException e) {
      throw new IllegalArgumentException(
          "Unable to read genesis validators root from beacon state "
              + source
              + ": "
              + e.getMessage(),
          e);
    }
  }
}
