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
package tech.pegasys.web3signer.slashingprotection.interchange.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SignedAttestation {

  private final String sourceEpoch;
  private final String targetEpoch;
  public String signingRoot;

  public SignedAttestation(
      @JsonProperty(value = "source_epoch", required = true) final String sourceEpoch,
      @JsonProperty(value = "target_epoch", required = true) final String targetEpoch,
      @JsonProperty(value = "signing_root") final String signingRoot) {
    this.sourceEpoch = sourceEpoch;
    this.targetEpoch = targetEpoch;
    this.signingRoot = signingRoot;
  }

  @JsonGetter(value = "source_epoch")
  public String getSourceEpoch() {
    return sourceEpoch;
  }

  @JsonGetter(value = "target_epoch")
  public String getTargetEpoch() {
    return targetEpoch;
  }

  @JsonGetter(value = "signing_root")
  public String getSigningRoot() {
    return signingRoot;
  }
}
