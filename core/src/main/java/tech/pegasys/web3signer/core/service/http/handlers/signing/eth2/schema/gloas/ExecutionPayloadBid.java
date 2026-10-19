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
package tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.gloas;

import tech.pegasys.teku.infrastructure.bytes.Bytes20;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBidSchema;
import tech.pegasys.teku.spec.datastructures.type.SszKZGCommitment;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.KZGCommitment;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.tuweni.bytes.Bytes32;

public class ExecutionPayloadBid {

  private final Bytes32 parentBlockHash;
  private final Bytes32 parentBlockRoot;
  private final Bytes32 blockHash;
  private final Bytes32 prevRandao;
  private final Bytes20 feeRecipient;
  private final UInt64 gasLimit;
  private final UInt64 builderIndex;
  private final UInt64 slot;
  private final UInt64 value;
  private final UInt64 executionPayment;
  private final List<KZGCommitment> blobKzgCommitments;
  private final Bytes32 executionRequestsRoot;

  @JsonCreator
  public ExecutionPayloadBid(
      @JsonProperty(value = "parent_block_hash", required = true) final Bytes32 parentBlockHash,
      @JsonProperty(value = "parent_block_root", required = true) final Bytes32 parentBlockRoot,
      @JsonProperty(value = "block_hash", required = true) final Bytes32 blockHash,
      @JsonProperty(value = "prev_randao", required = true) final Bytes32 prevRandao,
      @JsonProperty(value = "fee_recipient", required = true) final Bytes20 feeRecipient,
      @JsonProperty(value = "gas_limit", required = true) final UInt64 gasLimit,
      @JsonProperty(value = "builder_index", required = true) final UInt64 builderIndex,
      @JsonProperty(value = "slot", required = true) final UInt64 slot,
      @JsonProperty(value = "value", required = true) final UInt64 value,
      @JsonProperty(value = "execution_payment", required = true) final UInt64 executionPayment,
      @JsonProperty(value = "blob_kzg_commitments", required = true)
          final List<KZGCommitment> blobKzgCommitments,
      @JsonProperty(value = "execution_requests_root", required = true)
          final Bytes32 executionRequestsRoot) {
    this.parentBlockHash = parentBlockHash;
    this.parentBlockRoot = parentBlockRoot;
    this.blockHash = blockHash;
    this.prevRandao = prevRandao;
    this.feeRecipient = feeRecipient;
    this.gasLimit = gasLimit;
    this.builderIndex = builderIndex;
    this.slot = slot;
    this.value = value;
    this.executionPayment = executionPayment;
    this.blobKzgCommitments = blobKzgCommitments;
    this.executionRequestsRoot = executionRequestsRoot;
  }

  @JsonProperty("parent_block_hash")
  public Bytes32 getParentBlockHash() {
    return parentBlockHash;
  }

  @JsonProperty("parent_block_root")
  public Bytes32 getParentBlockRoot() {
    return parentBlockRoot;
  }

  @JsonProperty("block_hash")
  public Bytes32 getBlockHash() {
    return blockHash;
  }

  @JsonProperty("prev_randao")
  public Bytes32 getPrevRandao() {
    return prevRandao;
  }

  @JsonProperty("fee_recipient")
  public Bytes20 getFeeRecipient() {
    return feeRecipient;
  }

  @JsonProperty("gas_limit")
  public UInt64 getGasLimit() {
    return gasLimit;
  }

  @JsonProperty("builder_index")
  public UInt64 getBuilderIndex() {
    return builderIndex;
  }

  @JsonProperty("slot")
  public UInt64 getSlot() {
    return slot;
  }

  @JsonProperty("value")
  public UInt64 getValue() {
    return value;
  }

  @JsonProperty("execution_payment")
  public UInt64 getExecutionPayment() {
    return executionPayment;
  }

  @JsonProperty("blob_kzg_commitments")
  public List<KZGCommitment> getBlobKzgCommitments() {
    return blobKzgCommitments;
  }

  @JsonProperty("execution_requests_root")
  public Bytes32 getExecutionRequestsRoot() {
    return executionRequestsRoot;
  }

  public tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid
      asInternalExecutionPayloadBid(final SpecVersion specVersion) {
    final ExecutionPayloadBidSchema schema =
        SchemaDefinitionsGloas.required(specVersion.getSchemaDefinitions())
            .getExecutionPayloadBidSchema();
    final SszList<SszKZGCommitment> sszBlobKzgCommitments =
        blobKzgCommitments.stream()
            .map(c -> new SszKZGCommitment(c.asInternalKZGCommitment()))
            .collect(schema.getBlobKzgCommitmentsSchema().collector());
    return schema.create(
        parentBlockHash,
        parentBlockRoot,
        blockHash,
        prevRandao,
        feeRecipient,
        gasLimit,
        builderIndex,
        slot,
        value,
        executionPayment,
        sszBlobKzgCommitments,
        executionRequestsRoot);
  }
}
