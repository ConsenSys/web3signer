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
package tech.pegasys.web3signer.core.service.http.handlers.signing.eth2;

import tech.pegasys.teku.spec.SpecMilestone;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fork-versioned signing payload: {@code {"version": "<SpecMilestone>", "data": {...}}}. Used by
 * the Glamsterdam (ePBS) signing types so the signer can pick the SSZ schema for the named fork
 * rather than inferring it from the slot.
 */
public record VersionedRequest<T>(
    @JsonProperty(value = "version", required = true) SpecMilestone version,
    @JsonProperty(value = "data", required = true) T data) {}
