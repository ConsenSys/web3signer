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

import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsElectra;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.schema.electra.ExecutionRequests;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.tuweni.bytes.Bytes32;

public class ExecutionPayloadEnvelope {

  private final ExecutionPayloadGloas payload;
  private final ExecutionRequests executionRequests;
  private final UInt64 builderIndex;
  private final Bytes32 beaconBlockRoot;
  private final Bytes32 parentBeaconBlockRoot;

  @JsonCreator
  public ExecutionPayloadEnvelope(
      @JsonProperty(value = "payload", required = true) final ExecutionPayloadGloas payload,
      @JsonProperty(value = "execution_requests", required = true)
          final ExecutionRequests executionRequests,
      @JsonProperty(value = "builder_index", required = true) final UInt64 builderIndex,
      @JsonProperty(value = "beacon_block_root", required = true) final Bytes32 beaconBlockRoot,
      @JsonProperty(value = "parent_beacon_block_root", required = true)
          final Bytes32 parentBeaconBlockRoot) {
    this.payload = payload;
    this.executionRequests = executionRequests;
    this.builderIndex = builderIndex;
    this.beaconBlockRoot = beaconBlockRoot;
    this.parentBeaconBlockRoot = parentBeaconBlockRoot;
  }

  @JsonProperty("payload")
  public ExecutionPayloadGloas getPayload() {
    return payload;
  }

  @JsonProperty("execution_requests")
  public ExecutionRequests getExecutionRequests() {
    return executionRequests;
  }

  @JsonProperty("builder_index")
  public UInt64 getBuilderIndex() {
    return builderIndex;
  }

  @JsonProperty("beacon_block_root")
  public Bytes32 getBeaconBlockRoot() {
    return beaconBlockRoot;
  }

  @JsonProperty("parent_beacon_block_root")
  public Bytes32 getParentBeaconBlockRoot() {
    return parentBeaconBlockRoot;
  }

  /** The slot lives on the inner payload in Glamsterdam (ePBS); delegated for handler use. */
  public UInt64 getSlot() {
    return payload.slotNumber;
  }

  public tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadEnvelope
      asInternalExecutionPayloadEnvelope(final SpecVersion specVersion) {
    final SchemaDefinitionsGloas gloasSchemas =
        SchemaDefinitionsGloas.required(specVersion.getSchemaDefinitions());
    return gloasSchemas
        .getExecutionPayloadEnvelopeSchema()
        .create(
            payload.asInternalExecutionPayload(specVersion),
            executionRequests.asInternalExecutionRequests(
                SchemaDefinitionsElectra.required(specVersion.getSchemaDefinitions())
                    .getExecutionRequestsSchema()),
            builderIndex,
            beaconBlockRoot,
            parentBeaconBlockRoot);
  }
}
