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
package tech.pegasys.web3signer.keystorage.azure;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Unstable Azure client overrides, bundled so new ones can be added without changing every method
 * signature that threads them through (config parsing, factories, {@link AzureKeyVault}).
 *
 * @param endpointOverride overrides the Azure Key Vault endpoint (e.g. for testing against an
 *     emulator)
 * @param authorityHostOverride overrides the Microsoft Entra ID (Azure AD) authority host used to
 *     acquire tokens for client-secret authentication (e.g. sovereign clouds, or testing against an
 *     emulator that provides its own authority endpoint)
 * @param trustCertificateOverride trusts the given X.509 certificate file for TLS connections made
 *     by the Azure clients, in place of the platform's default trust store
 */
public record AzureOverrides(
    Optional<URI> endpointOverride,
    Optional<URI> authorityHostOverride,
    Optional<Path> trustCertificateOverride) {

  public static final AzureOverrides NONE =
      new AzureOverrides(Optional.empty(), Optional.empty(), Optional.empty());
}
