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
package tech.pegasys.eth2signer.core.service.operations;

import tech.pegasys.eth2signer.core.signing.ArtifactSignature;
import tech.pegasys.eth2signer.core.signing.ArtifactSignatureType;
import tech.pegasys.eth2signer.core.signing.ArtifactSigner;
import tech.pegasys.eth2signer.core.signing.ArtifactSignerProvider;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

public class SignerForIdentifier<T extends ArtifactSignature> {
  private static final Logger LOG = LogManager.getLogger();
  private final ArtifactSignerProvider signerProvider;
  private final SignatureFormatter<T> signatureFormatter;
  private final ArtifactSignatureType type;

  public SignerForIdentifier(
      final ArtifactSignerProvider signerProvider,
      final SignatureFormatter<T> signatureFormatter,
      final ArtifactSignatureType type) {
    this.signerProvider = signerProvider;
    this.signatureFormatter = signatureFormatter;
    this.type = type;
  }

  /**
   * Sign data for given identifier
   *
   * @param identifier The identifier for which to sign data.
   * @param data String in hex format which is signed
   * @return Optional String of signature (in hex format). Empty if no signer available for given
   *     identifier
   * @throws IllegalArgumentException if data is invalid i.e. not a valid hex string, null or empty.
   */
  public Optional<String> sign(final String identifier, final String data) {
    final Optional<ArtifactSigner> signer = signerProvider.getSigner(identifier);
    if (signer.isEmpty()) {
      return Optional.empty();
    }

    final Bytes dataToSign;
    try {
      if (StringUtils.isBlank(data)) {
        throw new IllegalArgumentException("Blank data");
      }
      dataToSign = Bytes.fromHexString(data);
    } catch (final IllegalArgumentException e) {
      LOG.debug("Invalid hex string {}", data, e);
      throw e;
    }
    final ArtifactSignature artifactSignature = signer.get().sign(dataToSign);
    return Optional.of(formatSignature(artifactSignature));
  }

  @SuppressWarnings("unchecked")
  private String formatSignature(final ArtifactSignature signature) {
    if (signature.getType() == type) {
      final T artifactSignature = (T) signature;
      return signatureFormatter.format(artifactSignature);
    } else {
      throw new IllegalStateException("Invalid signature type");
    }
  }
}