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
package tech.pegasys.web3signer.signing.config;

import tech.pegasys.web3signer.keystorage.common.MappedResults;
import tech.pegasys.web3signer.signing.ArtifactSigner;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Outcome of loading signers from one configured source (key config directory, keystore directory
 * or vault), including which files failed and whether the source failed as a whole.
 *
 * <p>{@link DefaultArtifactSignerProvider} records the source and file of every loaded signer. On a
 * reload, a previously loaded signer that is missing from the results is kept only if its source
 * failed as a whole or its own file is listed in {@link #failedFiles()}; otherwise it is treated as
 * removed.
 *
 * @param source name of the source, unique among the sources of one provider
 * @param signers signers loaded in this load, with the file each one was loaded from
 * @param failedFiles files that are present but failed to load
 * @param errorCount number of errors encountered while loading this source
 * @param sourceFailed {@code true} when failures cannot be attributed to files (directory not
 *     listable, shared password file unreadable, vault errors); every signer previously loaded from
 *     this source that is missing is then kept
 */
public record SignerSourceResult(
    String source,
    List<LoadedSigner> signers,
    Set<String> failedFiles,
    int errorCount,
    boolean sourceFailed) {

  /**
   * A loaded signer and the file it was loaded from.
   *
   * @param signer the signer
   * @param file the file the signer was loaded from; empty for vault sources
   */
  public record LoadedSigner(ArtifactSigner signer, Optional<String> file) {}

  public SignerSourceResult {
    signers = List.copyOf(signers);
    failedFiles = Set.copyOf(failedFiles);
  }

  /** The whole source failed before any file could be loaded. */
  public static SignerSourceResult sourceFailure(final String source) {
    return new SignerSourceResult(source, List.of(), Set.of(), 1, true);
  }

  /**
   * Result of a vault source. Vault loaders cannot attribute errors to secrets, so any error marks
   * the source as failed.
   */
  public static SignerSourceResult fromVault(
      final String source, final MappedResults<ArtifactSigner> results) {
    return new SignerSourceResult(
        source,
        results.getValues().stream()
            .map(signer -> new LoadedSigner(signer, Optional.empty()))
            .toList(),
        Set.of(),
        results.getErrorCount(),
        results.getErrorCount() > 0);
  }

  public List<ArtifactSigner> artifactSigners() {
    return signers.stream().map(LoadedSigner::signer).toList();
  }
}
