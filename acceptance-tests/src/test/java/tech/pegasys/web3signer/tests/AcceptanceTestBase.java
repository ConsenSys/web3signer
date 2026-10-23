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
package tech.pegasys.web3signer.tests;

import static tech.pegasys.web3signer.signing.KeyType.BLS;

import tech.pegasys.web3signer.dsl.signer.Signer;
import tech.pegasys.web3signer.dsl.signer.SignerConfiguration;
import tech.pegasys.web3signer.dsl.utils.DatabaseUtil;
import tech.pegasys.web3signer.signing.KeyType;

import java.security.SecureRandom;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class AcceptanceTestBase {
  protected static final SecureRandom SECURE_RANDOM = new SecureRandom();
  protected Signer signer;

  public static final Long DEFAULT_CHAIN_ID = 1337L;
  protected static final String ETH_2_SLASHINGPROTECTION_PERMITTED_SIGNINGS =
      "eth2_slashingprotection_permitted_signings_total";
  protected static final String ETH_2_SLASHINGPROTECTION_PREVENTED_SIGNINGS =
      "eth2_slashingprotection_prevented_signings_total";
  protected static final Set<String> ETH2_SLASHINGPROTECTION_METRICS =
      Set.of(
          ETH_2_SLASHINGPROTECTION_PERMITTED_SIGNINGS, ETH_2_SLASHINGPROTECTION_PREVENTED_SIGNINGS);

  protected void startSigner(final SignerConfiguration config) {
    signer = new Signer(config, null);
    signer.start();
    signer.awaitStartupCompletion();
  }

  @BeforeEach
  protected void resetSlashingProtectionDatabase() {
    // the embedded Postgres cluster is shared by the JVM, but each test gets its own database
    DatabaseUtil.reset();
  }

  @AfterEach
  protected void cleanup() {
    if (signer != null) {
      signer.shutdown();
      signer = null;
    }
    DatabaseUtil.reset();
  }

  public static String calculateMode(final KeyType keyType) {
    return (keyType == BLS) ? "eth2" : "eth1";
  }
}
