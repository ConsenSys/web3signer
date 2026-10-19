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
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsGloas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.tuweni.bytes.Bytes32;

public class PayloadAttestationData {

  private final Bytes32 beaconBlockRoot;
  private final UInt64 slot;
  private final boolean payloadPresent;
  private final boolean blobDataAvailable;

  @JsonCreator
  public PayloadAttestationData(
      @JsonProperty(value = "beacon_block_root", required = true) final Bytes32 beaconBlockRoot,
      @JsonProperty(value = "slot", required = true) final UInt64 slot,
      @JsonProperty(value = "payload_present", required = true) final boolean payloadPresent,
      @JsonProperty(value = "blob_data_available", required = true)
          final boolean blobDataAvailable) {
    this.beaconBlockRoot = beaconBlockRoot;
    this.slot = slot;
    this.payloadPresent = payloadPresent;
    this.blobDataAvailable = blobDataAvailable;
  }

  @JsonProperty("beacon_block_root")
  public Bytes32 getBeaconBlockRoot() {
    return beaconBlockRoot;
  }

  @JsonProperty("slot")
  public UInt64 getSlot() {
    return slot;
  }

  @JsonProperty("payload_present")
  public boolean isPayloadPresent() {
    return payloadPresent;
  }

  @JsonProperty("blob_data_available")
  public boolean isBlobDataAvailable() {
    return blobDataAvailable;
  }

  public tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.PayloadAttestationData
      asInternalPayloadAttestationData(final SpecVersion specVersion) {
    return SchemaDefinitionsGloas.required(specVersion.getSchemaDefinitions())
        .getPayloadAttestationDataSchema()
        .create(beaconBlockRoot, slot, payloadPresent, blobDataAvailable);
  }
}
