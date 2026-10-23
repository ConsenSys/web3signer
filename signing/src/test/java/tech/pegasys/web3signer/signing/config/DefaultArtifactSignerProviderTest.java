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
package tech.pegasys.web3signer.signing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.web3signer.BLSTestUtil;
import tech.pegasys.web3signer.KeystoreUtil;
import tech.pegasys.web3signer.keystorage.common.MappedResults;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.ArtifactSignerProvider;
import tech.pegasys.web3signer.signing.KeyType;
import tech.pegasys.web3signer.signing.secp256k1.EthPublicKeyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.WalletUtils;
import org.web3j.crypto.exception.CipherException;

class DefaultArtifactSignerProviderTest {

  private static final String PUBLIC_KEY1 =
      "0x989d34725a2bfc3f15105f3f5fc8741f436c25ee1ee4f948e425d6bcb8c56bce6e06c269635b7e985a7ffa639e2409bf";
  private static final String PUBLIC_KEY2 =
      "0xa99a76ed7796f7be22d5b7e85deeb7c5677e88e511e0b337618f8c4eb61349b4bf2d153f649f7b53359fe8b94a38e44c";
  private static final String PUBLIC_KEY3 =
      "0xb53d21a4cfd562c469cc81514d4ce5a6b577d8403d32a394dc265dd190b47fa9f829ffd7847571dd1164a4328cb4a738";

  private ArtifactSignerProvider signerProvider;

  @TempDir private Path commitBoostKeystoresPath;

  @TempDir private Path commitBoostPasswordDir;

  @AfterEach
  void cleanup() {
    if (signerProvider != null) {
      signerProvider.close();
    }
  }

  @Test
  void signerReturnedForMatchingIdentifier() {
    final ArtifactSigner mockSigner = mockSigner(PUBLIC_KEY1);

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner)),
            Optional.empty(),
            Optional.empty());
    assertThatCode(() -> signerProvider.load().get()).doesNotThrowAnyException();

    final Optional<ArtifactSigner> signer = signerProvider.getSigner(PUBLIC_KEY1);
    assertThat(signer).isNotEmpty();
    assertThat(signerProvider.availableIdentifiers()).containsOnly(PUBLIC_KEY1);
  }

  @Test
  void signerProviderOnlyHasSingleEntryIfPassedInListHasMultipleMatchingSigners() {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY1);

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner1, mockSigner2)),
            Optional.empty(),
            Optional.empty());
    assertThatCode(() -> signerProvider.load().get()).doesNotThrowAnyException();

    assertThat(signerProvider.availableIdentifiers()).hasSize(1);
    assertThat(signerProvider.availableIdentifiers()).containsOnly(PUBLIC_KEY1);
  }

  @Test
  void signerProviderCanMapInTwoSigners() {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner1, mockSigner2)),
            Optional.empty(),
            Optional.empty());
    assertThatCode(() -> signerProvider.load().get()).doesNotThrowAnyException();
    assertThat(signerProvider.availableIdentifiers()).hasSize(2);
    assertThat(signerProvider.availableIdentifiers()).containsOnly(PUBLIC_KEY1, PUBLIC_KEY2);
  }

  @Test
  void proxySignersAreLoadedCorrectly() throws IOException {

    // create random proxy signers
    final KeystoresParameters commitBoostParameters =
        new TestCommitBoostParameters(commitBoostKeystoresPath, commitBoostPasswordDir);

    // create random BLS key pairs as proxy keys for public key1 and public key2
    final List<BLSKeyPair> key1ProxyKeyPairs = randomBLSV4Keystores(PUBLIC_KEY1);
    final List<BLSKeyPair> key2ProxyKeyPairs = randomBLSV4Keystores(PUBLIC_KEY2);

    // create random secp key pairs as proxy keys for public key1 and public key2
    final List<ECKeyPair> key1SecpKeyPairs = randomSecpV3Keystores(PUBLIC_KEY1);
    final List<ECKeyPair> key2SecpKeyPairs = randomSecpV3Keystores(PUBLIC_KEY2);

    // set up mock signers
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner1, mockSigner2)),
            Optional.empty(),
            Optional.of(commitBoostParameters));

    // methods under test
    assertThatCode(() -> signerProvider.load().get()).doesNotThrowAnyException();

    // assert that the proxy keys are loaded correctly
    final Map<KeyType, Set<String>> key1ProxyPublicKeys =
        signerProvider.getProxyIdentifiers(PUBLIC_KEY1);

    assertThat(key1ProxyPublicKeys.get(KeyType.BLS))
        .containsExactlyInAnyOrder(getPublicKeysArray(key1ProxyKeyPairs));
    // proxy public keys are compressed
    assertThat(key1ProxyPublicKeys.get(KeyType.SECP256K1))
        .containsExactlyInAnyOrder(getCompressedSECPPublicKeysArray(key1SecpKeyPairs));

    final Map<KeyType, Set<String>> key2ProxyPublicKeys =
        signerProvider.getProxyIdentifiers(PUBLIC_KEY2);

    assertThat(key2ProxyPublicKeys.get(KeyType.BLS))
        .containsExactlyInAnyOrder(getPublicKeysArray(key2ProxyKeyPairs));
    assertThat(key2ProxyPublicKeys.get(KeyType.SECP256K1))
        .containsExactlyInAnyOrder(getCompressedSECPPublicKeysArray(key2SecpKeyPairs));
  }

  @Test
  void emptyProxySignersAreLoadedSuccessfully() {
    // enable commit boost without existing proxy keys
    final KeystoresParameters commitBoostParameters =
        new TestCommitBoostParameters(commitBoostKeystoresPath, commitBoostPasswordDir);

    // set up mock signers
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner1, mockSigner2)),
            Optional.empty(),
            Optional.of(commitBoostParameters));

    // methods under test
    assertThatCode(() -> signerProvider.load().get()).doesNotThrowAnyException();

    for (String identifier : List.of(PUBLIC_KEY1, PUBLIC_KEY2)) {
      final Map<KeyType, Set<String>> keyProxyPublicKeys =
          signerProvider.getProxyIdentifiers(identifier);
      assertThat(keyProxyPublicKeys).isEmpty();
    }
  }

  @Test
  void callbackReceivesOnlyAddedKeysOnFirstLoad() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);

    final AtomicReference<Set<String>> capturedAdded = new AtomicReference<>();
    final AtomicReference<Set<String>> capturedRemoved = new AtomicReference<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> {
          capturedAdded.set(added);
          capturedRemoved.set(removed);
        };

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner1, mockSigner2)),
            Optional.of(callback),
            Optional.empty());
    signerProvider.load().get();

    assertThat(capturedAdded.get()).containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
    assertThat(capturedRemoved.get()).isEmpty();
  }

  @Test
  void callbackReceivesDeltaOnReload() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);
    final ArtifactSigner mockSigner3 = mockSigner(PUBLIC_KEY3);

    final List<Set<String>> addedCaptures = new ArrayList<>();
    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> {
          addedCaptures.add(Set.copyOf(added));
          removedCaptures.add(Set.copyOf(removed));
        };

    // First load with keys 1 and 2
    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(List.of(sourceResult("key config files", mockSigner1, mockSigner2)));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // Reload with keys 2 and 3 (key1 removed, key3 added)
    resultsRef.set(List.of(sourceResult("key config files", mockSigner2, mockSigner3)));
    signerProvider.load().get();

    // First load: all keys are added
    assertThat(addedCaptures.get(0)).containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
    assertThat(removedCaptures.get(0)).isEmpty();

    // Second load: delta only
    assertThat(addedCaptures.get(1)).containsExactly(PUBLIC_KEY3);
    assertThat(removedCaptures.get(1)).containsExactly(PUBLIC_KEY1);
  }

  @Test
  void callbackReceivesEmptyDeltaWhenKeysUnchanged() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);

    final List<Set<String>> addedCaptures = new ArrayList<>();
    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> {
          addedCaptures.add(Set.copyOf(added));
          removedCaptures.add(Set.copyOf(removed));
        };

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner1)),
            Optional.of(callback),
            Optional.empty());
    signerProvider.load().get();
    signerProvider.load().get();

    // Second load: no changes
    assertThat(addedCaptures.get(1)).isEmpty();
    assertThat(removedCaptures.get(1)).isEmpty();
  }

  @Test
  void callbackReceivesAllKeysAsRemovedWhenReloadedWithEmpty() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);

    final List<Set<String>> addedCaptures = new ArrayList<>();
    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> {
          addedCaptures.add(Set.copyOf(added));
          removedCaptures.add(Set.copyOf(removed));
        };

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(List.of(sourceResult("key config files", mockSigner1, mockSigner2)));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // Reload with no keys
    resultsRef.set(List.of(sourceResult("key config files")));
    signerProvider.load().get();

    // Second load: all removed
    assertThat(addedCaptures.get(1)).isEmpty();
    assertThat(removedCaptures.get(1)).containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
  }

  @Test
  void signerOfFailedFileIsRetained() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);
    final String keystore1 = "/keystores/1.json";

    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> removedCaptures.add(Set.copyOf(removed));

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(
            List.of(
                fileResult(
                    "bls keystores",
                    Map.of(keystore1, mockSigner1, "/keystores/2.json", mockSigner2))));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // keystore 1 fails to load: the provider keeps its previously loaded signer
    resultsRef.set(
        List.of(
            fileResult(
                "bls keystores", Map.of("/keystores/2.json", mockSigner2), Set.of(keystore1))));
    final long errorCount = signerProvider.load().get();

    assertThat(errorCount).isEqualTo(1);
    assertThat(signerProvider.availableIdentifiers())
        .containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
    assertThat(removedCaptures.get(1)).isEmpty();
  }

  @Test
  void signerOfFailedSourceIsRetained() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);

    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> removedCaptures.add(Set.copyOf(removed));

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(
            List.of(
                fileResult(
                    "bls keystores",
                    Map.of("/keystores/1.json", mockSigner1, "/keystores/2.json", mockSigner2))));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // the whole source fails (e.g. unreadable password file): all its keys are kept
    resultsRef.set(List.of(SignerSourceResult.sourceFailure("bls keystores")));
    final long errorCount = signerProvider.load().get();

    assertThat(errorCount).isEqualTo(1);
    assertThat(signerProvider.availableIdentifiers())
        .containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
    assertThat(removedCaptures.get(1)).isEmpty();
  }

  @Test
  void signerOfDeletedFileIsRemovedWhileAnotherSourceFails() throws Exception {
    final ArtifactSigner mockSigner1 = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner mockSigner2 = mockSigner(PUBLIC_KEY2);
    final ArtifactSigner vaultSigner = mockSigner(PUBLIC_KEY3);

    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> removedCaptures.add(Set.copyOf(removed));

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(
            List.of(
                fileResult(
                    "bls keystores",
                    Map.of("/keystores/1.json", mockSigner1, "/keystores/2.json", mockSigner2)),
                vaultResult("Azure key vault", vaultSigner, 0)));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // keystore 1 is deleted while the vault reports an error: only key 1 is removed
    resultsRef.set(
        List.of(
            fileResult("bls keystores", Map.of("/keystores/2.json", mockSigner2)),
            vaultResult("Azure key vault", vaultSigner, 1)));
    final long errorCount = signerProvider.load().get();

    assertThat(errorCount).isEqualTo(1);
    assertThat(signerProvider.availableIdentifiers())
        .containsExactlyInAnyOrder(PUBLIC_KEY2, PUBLIC_KEY3);
    assertThat(removedCaptures.get(1)).containsExactly(PUBLIC_KEY1);
  }

  @Test
  void vaultKeysAreRetainedOnVaultErrorAndRemovedOnErrorFreeLoad() throws Exception {
    final ArtifactSigner vaultSigner = mockSigner(PUBLIC_KEY1);

    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> removedCaptures.add(Set.copyOf(removed));

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(List.of(vaultResult("AWS Secrets Manager", vaultSigner, 0)));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // vault error: the missing key is kept
    resultsRef.set(List.of(vaultResult("AWS Secrets Manager", null, 1)));
    assertThat(signerProvider.load().get()).isEqualTo(1);
    assertThat(signerProvider.availableIdentifiers()).containsExactly(PUBLIC_KEY1);
    assertThat(removedCaptures.get(1)).isEmpty();

    // error free load without the key: it was genuinely deleted in the vault
    resultsRef.set(List.of(vaultResult("AWS Secrets Manager", null, 0)));
    assertThat(signerProvider.load().get()).isZero();
    assertThat(signerProvider.availableIdentifiers()).isEmpty();
    assertThat(removedCaptures.get(2)).containsExactly(PUBLIC_KEY1);
  }

  @Test
  void signerAddedAtRuntimeIsRetainedWhileSourceFailsAndRemovedOnCleanLoad() throws Exception {
    final ArtifactSigner loadedSigner = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner importedSigner = mockSigner(PUBLIC_KEY2);

    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> removedCaptures.add(Set.copyOf(removed));

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(
            List.of(fileResult("key config files", Map.of("/config/1.yaml", loadedSigner))));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.of(callback), Optional.empty());
    signerProvider.load().get();

    // key manager API import: the key is added to the provider but not to the loaders
    signerProvider.addSigner(importedSigner).get();

    // a source error must not disable the imported key
    resultsRef.set(List.of(SignerSourceResult.sourceFailure("key config files")));
    assertThat(signerProvider.load().get()).isEqualTo(1);
    assertThat(signerProvider.availableIdentifiers())
        .containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
    assertThat(removedCaptures.get(1)).isEmpty();

    // once the source loads cleanly, the imported key is gone unless its file is on disk
    resultsRef.set(List.of(fileResult("key config files", Map.of("/config/1.yaml", loadedSigner))));
    assertThat(signerProvider.load().get()).isZero();
    assertThat(signerProvider.availableIdentifiers()).containsExactly(PUBLIC_KEY1);
    assertThat(removedCaptures.get(2)).containsExactly(PUBLIC_KEY2);
  }

  @Test
  void removedSignerIsNotResurrectedByLaterSourceFailure() throws Exception {
    final ArtifactSigner mockSigner = mockSigner(PUBLIC_KEY1);

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(
            List.of(fileResult("key config files", Map.of("/config/1.yaml", mockSigner))));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.empty(), Optional.empty());
    signerProvider.load().get();

    // key manager API delete: key removed from the provider and its files deleted from disk
    signerProvider.removeSigner(PUBLIC_KEY1).get();
    resultsRef.set(List.of(SignerSourceResult.sourceFailure("key config files")));
    signerProvider.load().get();

    assertThat(signerProvider.availableIdentifiers()).isEmpty();
  }

  @Test
  void signerReimportedAfterRemovalIsRetainedWhileAnotherSourceFails() throws Exception {
    final ArtifactSigner configSigner = mockSigner(PUBLIC_KEY1);
    final ArtifactSigner keystoreSigner = mockSigner(PUBLIC_KEY2);

    final AtomicReference<List<SignerSourceResult>> resultsRef =
        new AtomicReference<>(
            List.of(
                fileResult("key config files", Map.of("/config/1.yaml", configSigner)),
                fileResult("bls keystores", Map.of("/keystores/2.json", keystoreSigner))));
    signerProvider =
        new DefaultArtifactSignerProvider(resultsRef::get, Optional.empty(), Optional.empty());
    signerProvider.load().get();

    // key manager API delete followed by an import of the same key
    signerProvider.removeSigner(PUBLIC_KEY1).get();
    signerProvider.addSigner(configSigner).get();

    // the key config source no longer lists 1.yaml while the keystores source fails: the
    // re-imported key is a runtime key, not the deleted 1.yaml signer, so it is kept
    resultsRef.set(
        List.of(
            fileResult("key config files", Map.of()),
            SignerSourceResult.sourceFailure("bls keystores")));
    signerProvider.load().get();

    assertThat(signerProvider.availableIdentifiers())
        .containsExactlyInAnyOrder(PUBLIC_KEY1, PUBLIC_KEY2);
  }

  @Test
  void interruptedLoadKeepsCurrentSignersAndSkipsCallback() throws Exception {
    final ArtifactSigner mockSigner = mockSigner(PUBLIC_KEY1);

    final List<Set<String>> removedCaptures = new ArrayList<>();
    final BiConsumer<Set<String>, Set<String>> callback =
        (added, removed) -> removedCaptures.add(Set.copyOf(removed));

    final AtomicReference<Boolean> interrupt = new AtomicReference<>(false);
    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> {
              if (interrupt.get()) {
                // shutdown interrupts the loader thread while the sources are being read
                Thread.currentThread().interrupt();
                return List.of(fileResult("key config files", Map.of()));
              }
              return List.of(fileResult("key config files", Map.of("/config/1.yaml", mockSigner)));
            },
            Optional.of(callback),
            Optional.empty());
    signerProvider.load().get();

    interrupt.set(true);
    assertThatThrownBy(() -> signerProvider.load().get())
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(CancellationException.class);

    assertThat(signerProvider.availableIdentifiers()).containsExactly(PUBLIC_KEY1);
    assertThat(removedCaptures).hasSize(1);
  }

  @Test
  void proxySignersAreRetainedWhenProxyPasswordFileCannotBeRead() throws Exception {
    final KeystoresParameters commitBoostParameters =
        new TestCommitBoostParameters(commitBoostKeystoresPath, commitBoostPasswordDir);
    final List<BLSKeyPair> blsProxies = randomBLSV4Keystores(PUBLIC_KEY1);
    final List<ECKeyPair> secpProxies = randomSecpV3Keystores(PUBLIC_KEY1);

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner(PUBLIC_KEY1))),
            Optional.empty(),
            Optional.of(commitBoostParameters));
    signerProvider.load().get();

    Files.delete(commitBoostParameters.getKeystoresPasswordFile());
    signerProvider.load().get();

    final Map<KeyType, Set<String>> proxies = signerProvider.getProxyIdentifiers(PUBLIC_KEY1);
    assertThat(proxies.get(KeyType.BLS)).containsExactlyInAnyOrder(getPublicKeysArray(blsProxies));
    assertThat(proxies.get(KeyType.SECP256K1))
        .containsExactlyInAnyOrder(getCompressedSECPPublicKeysArray(secpProxies));
  }

  @Test
  void proxySignerOfFailedFileIsRetainedWhileDeletedProxySignerIsRemoved() throws Exception {
    final KeystoresParameters commitBoostParameters =
        new TestCommitBoostParameters(commitBoostKeystoresPath, commitBoostPasswordDir);
    final List<BLSKeyPair> blsProxies = randomBLSV4Keystores(PUBLIC_KEY1);
    final Path blsProxyDir = commitBoostKeystoresPath.resolve(PUBLIC_KEY1).resolve("BLS");

    signerProvider =
        new DefaultArtifactSignerProvider(
            () -> List.of(sourceResult("key config files", mockSigner(PUBLIC_KEY1))),
            Optional.empty(),
            Optional.of(commitBoostParameters));
    signerProvider.load().get();

    // proxy 0 becomes unreadable, proxy 1 is deleted
    final String failedProxy = blsProxies.get(0).getPublicKey().toString();
    final String deletedProxy = blsProxies.get(1).getPublicKey().toString();
    Files.writeString(blsProxyDir.resolve(failedProxy + ".json"), "not a keystore");
    Files.delete(blsProxyDir.resolve(deletedProxy + ".json"));
    signerProvider.load().get();

    assertThat(signerProvider.getProxyIdentifiers(PUBLIC_KEY1).get(KeyType.BLS))
        .containsExactlyInAnyOrder(
            failedProxy,
            blsProxies.get(2).getPublicKey().toString(),
            blsProxies.get(3).getPublicKey().toString());
  }

  private static ArtifactSigner mockSigner(final String identifier) {
    final ArtifactSigner mockSigner = mock(ArtifactSigner.class);
    when(mockSigner.getIdentifier()).thenReturn(identifier);
    return mockSigner;
  }

  /** A source result whose signers were loaded from files (or, without files, from a vault). */
  private static SignerSourceResult sourceResult(
      final String source, final ArtifactSigner... signers) {
    return new SignerSourceResult(
        source,
        Arrays.stream(signers)
            .map(signer -> new SignerSourceResult.LoadedSigner(signer, Optional.empty()))
            .toList(),
        Set.of(),
        0,
        false);
  }

  /** A file based source result, with the given failed files (each counting as one error). */
  private static SignerSourceResult fileResult(
      final String source,
      final Map<String, ArtifactSigner> signerByFile,
      final Set<String> failedFiles) {
    return new SignerSourceResult(
        source,
        signerByFile.entrySet().stream()
            .map(
                entry ->
                    new SignerSourceResult.LoadedSigner(
                        entry.getValue(), Optional.of(entry.getKey())))
            .toList(),
        failedFiles,
        failedFiles.size(),
        false);
  }

  private static SignerSourceResult fileResult(
      final String source, final Map<String, ArtifactSigner> signerByFile) {
    return fileResult(source, signerByFile, Set.of());
  }

  private static SignerSourceResult vaultResult(
      final String source, final ArtifactSigner signer, final int errorCount) {
    return SignerSourceResult.fromVault(
        source,
        signer == null
            ? MappedResults.newInstance(List.of(), errorCount)
            : MappedResults.newInstance(List.of(signer), errorCount));
  }

  private List<BLSKeyPair> randomBLSV4Keystores(final String identifier) throws IOException {
    final Path v4Dir =
        Files.createDirectories(
            commitBoostKeystoresPath.resolve(identifier).resolve(KeyType.BLS.name()));

    return IntStream.range(0, 4)
        .mapToObj(
            i -> {
              final BLSKeyPair blsKeyPair = BLSTestUtil.randomKeyPair(i);
              KeystoreUtil.createKeystoreFile(blsKeyPair, v4Dir, "password");
              return blsKeyPair;
            })
        .toList();
  }

  private List<ECKeyPair> randomSecpV3Keystores(final String identifier) throws IOException {
    final Path v3Dir =
        Files.createDirectories(
            commitBoostKeystoresPath.resolve(identifier).resolve(KeyType.SECP256K1.name()));
    return IntStream.range(0, 4)
        .mapToObj(
            i -> {
              try {
                final KeyPair secp256k1KeyPair = EthPublicKeyUtils.generateK256KeyPair();
                final ECKeyPair ecKeyPair = ECKeyPair.create(secp256k1KeyPair);

                WalletUtils.generateWalletFile("password", ecKeyPair, v3Dir.toFile(), false);
                return ecKeyPair;
              } catch (final CipherException | IOException e) {
                throw new RuntimeException(e);
              }
            })
        .toList();
  }

  private static String[] getPublicKeysArray(final List<BLSKeyPair> blsKeyPairs) {
    return blsKeyPairs.stream()
        .map(keyPair -> keyPair.getPublicKey().toString())
        .toList()
        .toArray(String[]::new);
  }

  private static String[] getCompressedSECPPublicKeysArray(final List<ECKeyPair> ecKeyPairs) {
    // compressed public keys
    return ecKeyPairs.stream()
        .map(
            keyPair ->
                EthPublicKeyUtils.toHexStringCompressed(
                    EthPublicKeyUtils.web3JPublicKeyToECPublicKey(keyPair.getPublicKey())))
        .toList()
        .toArray(String[]::new);
  }
}
