/*
 * Copyright 2026 Consensys Incorporated.
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
package tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.electra;

import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.spec.datastructures.execution.versions.gloas.BuilderExitRequestSchema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EIP-8282 builder exit request. Introduced in Gloas (ePBS) as one of the execution requests that
 * voluntarily exits a registered builder.
 */
public class BuilderExitRequest {

  @JsonProperty("source_address")
  private final Eth1Address sourceAddress;

  @JsonProperty("pubkey")
  private final BLSPublicKey pubkey;

  public BuilderExitRequest(
      @JsonProperty("source_address") final Eth1Address sourceAddress,
      @JsonProperty("pubkey") final BLSPublicKey pubkey) {
    this.sourceAddress = sourceAddress;
    this.pubkey = pubkey;
  }

  public BuilderExitRequest(
      final tech.pegasys.teku.spec.datastructures.execution.versions.gloas.BuilderExitRequest
          builderExitRequest) {
    this.sourceAddress =
        Eth1Address.fromBytes(builderExitRequest.getSourceAddress().getWrappedBytes());
    this.pubkey = builderExitRequest.getPubkey();
  }

  public final tech.pegasys.teku.spec.datastructures.execution.versions.gloas.BuilderExitRequest
      asInternalBuilderExitRequest(final BuilderExitRequestSchema schema) {
    return schema.create(sourceAddress, pubkey);
  }
}
