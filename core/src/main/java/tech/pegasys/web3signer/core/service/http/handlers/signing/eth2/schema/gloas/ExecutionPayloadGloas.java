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
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadBuilder;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadSchema;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.capella.Withdrawal;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.deneb.ExecutionPayloadDeneb;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

public class ExecutionPayloadGloas extends ExecutionPayloadDeneb {

  @JsonProperty("block_access_list")
  public final Bytes blockAccessList;

  @JsonProperty("slot_number")
  public final UInt64 slotNumber;

  @JsonCreator
  public ExecutionPayloadGloas(
      @JsonProperty("parent_hash") final Bytes32 parentHash,
      @JsonProperty("fee_recipient") final Bytes20 feeRecipient,
      @JsonProperty("state_root") final Bytes32 stateRoot,
      @JsonProperty("receipts_root") final Bytes32 receiptsRoot,
      @JsonProperty("logs_bloom") final Bytes logsBloom,
      @JsonProperty("prev_randao") final Bytes32 prevRandao,
      @JsonProperty("block_number") final UInt64 blockNumber,
      @JsonProperty("gas_limit") final UInt64 gasLimit,
      @JsonProperty("gas_used") final UInt64 gasUsed,
      @JsonProperty("timestamp") final UInt64 timestamp,
      @JsonProperty("extra_data") final Bytes extraData,
      @JsonProperty("base_fee_per_gas") final UInt256 baseFeePerGas,
      @JsonProperty("block_hash") final Bytes32 blockHash,
      @JsonProperty("transactions") final List<Bytes> transactions,
      @JsonProperty("withdrawals") final List<Withdrawal> withdrawals,
      @JsonProperty("blob_gas_used") final UInt64 blobGasUsed,
      @JsonProperty("excess_blob_gas") final UInt64 excessBlobGas,
      @JsonProperty("block_access_list") final Bytes blockAccessList,
      @JsonProperty("slot_number") final UInt64 slotNumber) {
    super(
        parentHash,
        feeRecipient,
        stateRoot,
        receiptsRoot,
        logsBloom,
        prevRandao,
        blockNumber,
        gasLimit,
        gasUsed,
        timestamp,
        extraData,
        baseFeePerGas,
        blockHash,
        transactions,
        withdrawals,
        blobGasUsed,
        excessBlobGas);
    this.blockAccessList = blockAccessList;
    this.slotNumber = slotNumber;
  }

  public ExecutionPayloadGloas(
      final tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload executionPayload) {
    super(executionPayload);
    final tech.pegasys.teku.spec.datastructures.execution.versions.gloas.ExecutionPayloadGloas
        gloasPayload =
            tech.pegasys.teku.spec.datastructures.execution.versions.gloas.ExecutionPayloadGloas
                .required(executionPayload);
    this.blockAccessList = gloasPayload.getBlockAccessList().getBytes();
    this.slotNumber = gloasPayload.getSlotNumber();
  }

  @Override
  protected ExecutionPayloadBuilder applyToBuilder(
      final ExecutionPayloadSchema<?> executionPayloadSchema,
      final ExecutionPayloadBuilder builder) {
    return super.applyToBuilder(executionPayloadSchema, builder)
        .blockAccessList(() -> blockAccessList)
        .slotNumber(() -> slotNumber);
  }

  @Override
  public boolean equals(final Object o) {
    if (!(o instanceof ExecutionPayloadGloas that)) return false;
    if (!super.equals(o)) return false;
    return Objects.equals(blockAccessList, that.blockAccessList)
        && Objects.equals(slotNumber, that.slotNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), blockAccessList, slotNumber);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("parentHash", parentHash)
        .add("feeRecipient", feeRecipient)
        .add("stateRoot", stateRoot)
        .add("receiptsRoot", receiptsRoot)
        .add("logsBloom", logsBloom)
        .add("prevRandao", prevRandao)
        .add("blockNumber", blockNumber)
        .add("gasLimit", gasLimit)
        .add("gasUsed", gasUsed)
        .add("timestamp", timestamp)
        .add("extraData", extraData)
        .add("baseFeePerGas", baseFeePerGas)
        .add("blockHash", blockHash)
        .add("transactions", transactions)
        .add("withdrawals", withdrawals)
        .add("blobGasUsed", blobGasUsed)
        .add("excessBlobGas", excessBlobGas)
        .add("blockAccessList", blockAccessList)
        .add("slotNumber", slotNumber)
        .toString();
  }
}
