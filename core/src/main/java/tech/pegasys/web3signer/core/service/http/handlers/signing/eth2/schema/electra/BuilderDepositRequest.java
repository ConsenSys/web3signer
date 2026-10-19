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

import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.execution.versions.gloas.BuilderDepositRequestSchema;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BLSPubKey;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.BLSSignature;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.tuweni.bytes.Bytes32;

/**
 * EIP-8282 builder deposit request. Introduced in Gloas (ePBS) as one of the execution requests
 * that registers a builder via the deposit contract.
 */
public class BuilderDepositRequest {

  @JsonProperty("pubkey")
  private final BLSPubKey pubkey;

  @JsonProperty("withdrawal_credentials")
  private final Bytes32 withdrawalCredentials;

  @JsonProperty("amount")
  private final UInt64 amount;

  @JsonProperty("signature")
  private final BLSSignature signature;

  public BuilderDepositRequest(
      @JsonProperty("pubkey") final BLSPubKey pubkey,
      @JsonProperty("withdrawal_credentials") final Bytes32 withdrawalCredentials,
      @JsonProperty("amount") final UInt64 amount,
      @JsonProperty("signature") final BLSSignature signature) {
    this.pubkey = pubkey;
    this.withdrawalCredentials = withdrawalCredentials;
    this.amount = amount;
    this.signature = signature;
  }

  public BuilderDepositRequest(
      final tech.pegasys.teku.spec.datastructures.execution.versions.gloas.BuilderDepositRequest
          builderDepositRequest) {
    this.pubkey = new BLSPubKey(builderDepositRequest.getPubkey());
    this.withdrawalCredentials = builderDepositRequest.getWithdrawalCredentials();
    this.amount = builderDepositRequest.getAmount();
    this.signature = new BLSSignature(builderDepositRequest.getSignature());
  }

  public final tech.pegasys.teku.spec.datastructures.execution.versions.gloas.BuilderDepositRequest
      asInternalBuilderDepositRequest(final BuilderDepositRequestSchema schema) {
    return schema.create(
        pubkey.asBLSPublicKey(), withdrawalCredentials, amount, signature.asInternalBLSSignature());
  }
}
