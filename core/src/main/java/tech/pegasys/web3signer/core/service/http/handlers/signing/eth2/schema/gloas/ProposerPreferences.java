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

import tech.pegasys.teku.ethereum.execution.types.Eth1Address;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.tuweni.bytes.Bytes32;

public class ProposerPreferences {

  private final Bytes32 dependentRoot;
  private final UInt64 proposalSlot;
  private final UInt64 validatorIndex;
  private final Eth1Address feeRecipient;
  private final UInt64 targetGasLimit;

  @JsonCreator
  public ProposerPreferences(
      @JsonProperty(value = "dependent_root", required = true) final Bytes32 dependentRoot,
      @JsonProperty(value = "proposal_slot", required = true) final UInt64 proposalSlot,
      @JsonProperty(value = "validator_index", required = true) final UInt64 validatorIndex,
      @JsonProperty(value = "fee_recipient", required = true) final Eth1Address feeRecipient,
      @JsonProperty(value = "target_gas_limit", required = true) final UInt64 targetGasLimit) {
    this.dependentRoot = dependentRoot;
    this.proposalSlot = proposalSlot;
    this.validatorIndex = validatorIndex;
    this.feeRecipient = feeRecipient;
    this.targetGasLimit = targetGasLimit;
  }

  @JsonProperty("dependent_root")
  public Bytes32 getDependentRoot() {
    return dependentRoot;
  }

  @JsonProperty("proposal_slot")
  public UInt64 getProposalSlot() {
    return proposalSlot;
  }

  @JsonProperty("validator_index")
  public UInt64 getValidatorIndex() {
    return validatorIndex;
  }

  @JsonProperty("fee_recipient")
  public Eth1Address getFeeRecipient() {
    return feeRecipient;
  }

  @JsonProperty("target_gas_limit")
  public UInt64 getTargetGasLimit() {
    return targetGasLimit;
  }

  public tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ProposerPreferences
      asInternalProposerPreferences(final SpecVersion specVersion) {
    return SchemaDefinitionsGloas.required(specVersion.getSchemaDefinitions())
        .getProposerPreferencesSchema()
        .create(dependentRoot, proposalSlot, validatorIndex, feeRecipient, targetGasLimit);
  }
}
