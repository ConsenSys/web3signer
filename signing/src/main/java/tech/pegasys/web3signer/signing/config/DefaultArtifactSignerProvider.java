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

import static tech.pegasys.web3signer.signing.KeyType.BLS;
import static tech.pegasys.web3signer.signing.KeyType.SECP256K1;

import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.ArtifactSignerProvider;
import tech.pegasys.web3signer.signing.KeyType;
import tech.pegasys.web3signer.signing.bulkloading.BlsKeystoreBulkLoader;
import tech.pegasys.web3signer.signing.bulkloading.SecpV3KeystoresBulkLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.base.Suppliers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of {@link ArtifactSignerProvider} that loads signers and proxy signers.
 *
 * <p>This class is optimized for read-heavy workloads where signing operations vastly outnumber
 * reload operations. It uses a volatile reference with immutable map swap pattern to provide:
 *
 * <ul>
 *   <li><b>Lock-free reads:</b> {@code getSigner()}, {@code getProxySigner()}, and other read
 *       operations perform a single volatile read with no synchronization overhead.
 *   <li><b>Atomic snapshots:</b> Readers see either the complete old state or the complete new
 *       state, never a mix of both during reload operations.
 *   <li><b>Memory efficiency:</b> Immutable maps have lower overhead compared to concurrent
 *       collections, and old maps become eligible for garbage collection immediately after swap.
 * </ul>
 *
 * <p>Write operations ({@code load()}, {@code addSigner()}, {@code removeSigner()}, {@code
 * addProxySigner()}) are serialized through a single-threaded executor to ensure consistency. These
 * operations use copy-on-write semantics, building a new immutable map and atomically swapping the
 * volatile reference.
 *
 * <p>Thread safety guarantees:
 *
 * <ul>
 *   <li>All read operations are thread-safe and can be called concurrently from multiple Vert.x
 *       event loop threads.
 *   <li>Write operations are serialized and thread-safe.
 *   <li>Reads during writes will see either the complete old state or the complete new state.
 * </ul>
 *
 * @see ArtifactSignerProvider
 */
public class DefaultArtifactSignerProvider implements ArtifactSignerProvider {

  private static final Logger LOG = LogManager.getLogger();

  private final Supplier<List<SignerSourceResult>> artifactSignerResultsSupplier;
  private final Optional<BiConsumer<Set<String>, Set<String>>> postLoadingCallback;
  private final Optional<KeystoresParameters> commitBoostKeystoresParameters;

  // Volatile references to immutable maps - readers see atomic snapshots
  private volatile SignerState state = new SignerState(Map.of(), Map.of(), Map.of(), Map.of());

  private record SignerState(
      Map<String, ArtifactSigner> signers,
      Map<String, Set<ArtifactSigner>> proxySigners,
      Map<String, Provenance> provenance,
      Map<String, Provenance> proxyProvenance) {}

  /**
   * Where a signer was loaded from. Entries of signers removed through {@link #removeSigner} are
   * left in place: retention only consults the provenance of signers that are still loaded, and
   * every {@link #load()} rebuilds the provenance maps from its results.
   *
   * @param source name of the loader source the signer came from; the proxy keystore directory for
   *     proxy signers
   * @param file file the signer was loaded from, {@code null} for vault sources
   */
  private record Provenance(String source, String file) {}

  private final ExecutorService executorService =
      Executors.newSingleThreadExecutor(
          r -> {
            final Thread thread = new Thread(r, "artifact-signer-loader");
            thread.setDaemon(true);
            return thread;
          });

  public DefaultArtifactSignerProvider(
      final Supplier<List<SignerSourceResult>> artifactSignerResultsSupplier,
      final Optional<BiConsumer<Set<String>, Set<String>>> postLoadingCallback,
      final Optional<KeystoresParameters> commitBoostKeystoresParameters) {
    this.artifactSignerResultsSupplier = artifactSignerResultsSupplier;
    this.postLoadingCallback = postLoadingCallback;
    this.commitBoostKeystoresParameters = commitBoostKeystoresParameters;
  }

  /**
   * Loads or reloads all signers and proxy signers from configured sources.
   *
   * <p>This operation is executed asynchronously on a single-threaded executor to ensure
   * serialization with other write operations. The loading process:
   *
   * <ol>
   *   <li>Invokes the {@code artifactSignerResultsSupplier} to load signers from all configured
   *       sources (encrypted keystores, AWS KMS, Azure Key Vault, GCP Secret Manager, HashiCorp
   *       Vault, etc.), capturing both successfully loaded signers and error counts
   *   <li>Builds a new immutable signers map from successfully loaded signers, handling duplicate
   *       keys by keeping the first signer and logging a warning
   *   <li>If commit boost parameters are enabled, loads proxy signers for each consensus public
   *       key, keeping previously loaded proxy signers whose directory or file failed to load
   *   <li>Atomically swaps the volatile references to the new immutable maps, unless the load was
   *       interrupted
   *   <li>Invokes the optional post-loading callback (e.g., for slashing protection registration)
   * </ol>
   *
   * <p>The atomic swap ensures that concurrent readers see either the complete old state or the
   * complete new state, never a mix. This is critical for maintaining consistency during signing
   * operations while a reload is in progress.
   *
   * <p><b>Error Handling:</b> Individual signer loading failures (e.g., malformed keystores,
   * invalid keys) are counted but do not prevent the loading of other valid signers. Successfully
   * loaded signers are made available even if some configurations fail. The returned error count
   * allows callers to detect partial failures. A previously loaded signer that is missing from the
   * results is kept when its source failed as a whole or its own file failed to load, so that a
   * failure does not look like a removal; a signer that is missing while its source loaded
   * completely is genuinely removed.
   *
   * <p>This method is typically invoked:
   *
   * <ul>
   *   <li>At application startup to perform initial loading of signers
   *   <li>Via the {@code /reload} API endpoint to refresh signers without restarting the
   *       application
   * </ul>
   *
   * <p><b>Note:</b> Loading can be a time-consuming operation, especially when loading from remote
   * vaults or decrypting many keystores. The returned {@link Future} completes when all signers
   * have been processed (both successful and failed) and the state swap has occurred.
   *
   * @return a {@link Future} containing the number of signer configurations that failed to load (0
   *     indicates all signers loaded successfully)
   */
  @Override
  public Future<Long> load() {
    return executorService.submit(
        () -> {
          final SignerState currentState = this.state;
          LOG.debug("Signer keys pre-loaded in memory {}", currentState.signers.size());

          // Get per-source results - includes error counts and the failed files of each source
          final List<SignerSourceResult> results = artifactSignerResultsSupplier.get();
          final long errorCount = results.stream().mapToLong(SignerSourceResult::errorCount).sum();

          // Build new signers map from successful results, remembering where each one came from
          final Map<String, ArtifactSigner> newSigners = new HashMap<>();
          final Map<String, Provenance> newProvenance = new HashMap<>();
          for (final SignerSourceResult result : results) {
            for (final SignerSourceResult.LoadedSigner loaded : result.signers()) {
              final String identifier = loaded.signer().getIdentifier();
              if (newSigners.putIfAbsent(identifier, loaded.signer()) != null) {
                LOG.warn("Duplicate key found while loading: {}", identifier);
                continue;
              }
              newProvenance.put(
                  identifier, new Provenance(result.source(), loaded.file().orElse(null)));
            }
          }

          retainSignersOfFailedSources(
              currentState, results, errorCount, newSigners, newProvenance);

          // Build new proxy signers map
          final Map<String, Set<ArtifactSigner>> newProxySigners = new HashMap<>();
          final Map<String, Provenance> newProxyProvenance = new HashMap<>();
          commitBoostKeystoresParameters
              .filter(KeystoresParameters::isEnabled)
              .ifPresent(
                  keystoreParameter ->
                      loadProxySigners(
                          keystoreParameter,
                          currentState,
                          newSigners.keySet(),
                          newProxySigners,
                          newProxyProvenance));

          // An interrupted load (shutdown) may be missing signers that were not deleted: keep the
          // current state rather than publishing it and reporting them as removed
          if (Thread.currentThread().isInterrupted()) {
            LOG.warn("Signer loading was interrupted; keeping the currently loaded signers");
            throw new CancellationException("Signer loading was interrupted");
          }

          // Atomically publish new immutable state - Map.copyOf() ensures readers never see
          // intermediate modifications, volatile write guarantees immediate visibility across
          // threads
          state =
              new SignerState(
                  Map.copyOf(newSigners),
                  Map.copyOf(newProxySigners),
                  Map.copyOf(newProvenance),
                  Map.copyOf(newProxyProvenance));

          // Compute delta and invoke callback only when callback is configured
          postLoadingCallback.ifPresent(
              callback -> {
                final Set<String> addedKeys = new HashSet<>(state.signers.keySet());
                addedKeys.removeAll(currentState.signers.keySet());

                final Set<String> removedKeys = new HashSet<>(currentState.signers.keySet());
                removedKeys.removeAll(state.signers.keySet());

                callback.accept(addedKeys, removedKeys);
              });

          LOG.info("Total signers (keys) currently loaded in memory: {}", state.signers.size());
          return errorCount;
        });
  }

  @Override
  public Optional<ArtifactSigner> getSigner(final String identifier) {
    final SignerState currentState = this.state;
    // Single volatile read - no locking
    final Optional<ArtifactSigner> result =
        Optional.ofNullable(currentState.signers.get(identifier));
    if (result.isEmpty()) {
      LOG.debug("No signer was loaded matching identifier '{}'", identifier);
    }
    return result;
  }

  @Override
  public Optional<ArtifactSigner> getProxySigner(final String proxyPubKey) {
    // Single volatile read, then stream over immutable collections
    final SignerState currentState = this.state;
    return currentState.proxySigners.values().stream()
        .flatMap(Set::stream)
        .filter(signer -> signer.getIdentifier().equals(proxyPubKey))
        .findFirst();
  }

  @Override
  public Set<String> availableIdentifiers() {
    // signers is already immutable, keySet() returns immutable view
    final SignerState currentState = this.state;
    return currentState.signers.keySet();
  }

  @Override
  public Map<KeyType, Set<String>> getProxyIdentifiers(final String consensusPubKey) {
    // Pure read - no writes!
    final SignerState currentState = this.state;
    final Set<ArtifactSigner> proxies =
        currentState.proxySigners.getOrDefault(consensusPubKey, Set.of());

    if (proxies.isEmpty()) {
      return Map.of();
    }

    return proxies.stream()
        .collect(
            Collectors.groupingBy(
                ArtifactSigner::getKeyType,
                Collectors.mapping(ArtifactSigner::getIdentifier, Collectors.toUnmodifiableSet())));
  }

  @Override
  public Future<Void> addSigner(final ArtifactSigner signer) {
    return executorService.submit(
        () -> {
          final SignerState currentState = this.state;

          final Map<String, ArtifactSigner> newSigners = new HashMap<>(currentState.signers);
          final ArtifactSigner existing = newSigners.put(signer.getIdentifier(), signer);
          if (existing != null) {
            LOG.warn("Replaced existing signer for identifier '{}'", signer.getIdentifier());
          }

          final Map<String, Set<ArtifactSigner>> newProxySigners =
              new HashMap<>(currentState.proxySigners);
          newProxySigners.putIfAbsent(signer.getIdentifier(), Set.of());

          // Atomically publish new immutable state - Map.copyOf() ensures readers never see
          // intermediate modifications, volatile write guarantees immediate visibility across
          // threads. The signer was added at runtime, so it has no source provenance; an entry
          // left behind by an earlier removeSigner of the same key is dropped.
          state =
              new SignerState(
                  Map.copyOf(newSigners),
                  Map.copyOf(newProxySigners),
                  withoutEntry(currentState.provenance, signer.getIdentifier()),
                  currentState.proxyProvenance);
          LOG.info("Loaded new signer for identifier '{}'", signer.getIdentifier());
          return null;
        });
  }

  @Override
  public Future<Void> removeSigner(final String identifier) {
    return executorService.submit(
        () -> {
          final SignerState currentState = this.state;

          final Map<String, ArtifactSigner> newSigners = new HashMap<>(currentState.signers);
          newSigners.remove(identifier);
          final Map<String, Set<ArtifactSigner>> newProxySigners =
              new HashMap<>(currentState.proxySigners);
          newProxySigners.remove(identifier);

          // Atomically publish new immutable state - Map.copyOf() ensures readers never see
          // intermediate modifications, volatile write guarantees immediate visibility across
          // threads. Provenance is left in place (see Provenance) to keep removal at two map
          // copies.
          this.state =
              new SignerState(
                  Map.copyOf(newSigners),
                  Map.copyOf(newProxySigners),
                  currentState.provenance,
                  currentState.proxyProvenance);

          LOG.info("Removed signer with identifier '{}'", identifier);
          return null;
        });
  }

  /**
   * Adds a proxy signer associated with a consensus public key.
   *
   * <p>This operation is executed asynchronously on a single-threaded executor to ensure
   * serialization with other write operations. The method uses copy-on-write semantics:
   *
   * <ol>
   *   <li>Creates a mutable copy of the current proxy signers map
   *   <li>Adds the new proxy signer to the set for the given consensus public key
   *   <li>Atomically swaps the volatile reference to the new immutable map
   * </ol>
   *
   * <p>Concurrent readers will see either the state before or after the addition, never an
   * intermediate state.
   *
   * @param signerToAdd the proxy signer to add
   * @param consensusPubKey the consensus public key to associate with the proxy signer
   * @return a {@link Future} that completes when the proxy signer has been added
   */
  @Override
  public Future<Void> addProxySigner(
      final ArtifactSigner signerToAdd, final String consensusPubKey) {
    return executorService.submit(
        () -> {
          final SignerState currentState = this.state;

          final Map<String, Set<ArtifactSigner>> newProxySigners =
              new HashMap<>(currentState.proxySigners);
          final Set<ArtifactSigner> existingProxies =
              newProxySigners.getOrDefault(consensusPubKey, Set.of());
          final Set<ArtifactSigner> updatedProxies = new HashSet<>(existingProxies);
          updatedProxies.add(signerToAdd);
          newProxySigners.put(consensusPubKey, Set.copyOf(updatedProxies));

          // Atomically publish new immutable state - Map.copyOf() ensures readers never see
          // intermediate modifications, volatile write guarantees immediate visibility across
          // threads
          this.state =
              new SignerState(
                  currentState.signers,
                  Map.copyOf(newProxySigners),
                  currentState.provenance,
                  withoutEntry(currentState.proxyProvenance, signerToAdd.getIdentifier()));

          LOG.info(
              "Loaded new proxy signer {} for consensus public key '{}'",
              signerToAdd.getIdentifier(),
              consensusPubKey);
          return null;
        });
  }

  @Override
  public void close() {
    // Immediate shutdown is appropriate here since if the app is shutting down,
    // there's no need to wait for a potentially long-running load operation
    executorService.shutdownNow();
  }

  /** Copies the map without the key only when the key is present; otherwise returns it as is. */
  private static Map<String, Provenance> withoutEntry(
      final Map<String, Provenance> provenance, final String identifier) {
    if (!provenance.containsKey(identifier)) {
      return provenance;
    }
    final Map<String, Provenance> copy = new HashMap<>(provenance);
    copy.remove(identifier);
    return Map.copyOf(copy);
  }

  /**
   * Loads the proxy signers of every consensus public key from the commit boost keystores path. The
   * directory structure expected is:
   *
   * <pre>
   * {keystoresPath}/{consensusPubKey}/{keyType}/
   * </pre>
   *
   * <p>A previously loaded proxy signer that is missing is kept only when its directory failed as a
   * whole (unreadable directory or password file) or its own file failed to load; a proxy directory
   * that does not exist, or exists but is empty, has no proxy signers. When the keystores path
   * itself cannot be read, every previously loaded proxy signer is kept. The password file is read
   * at most once per load, and only when a proxy directory exists.
   */
  private static void loadProxySigners(
      final KeystoresParameters keystoreParameter,
      final SignerState currentState,
      final Set<String> consensusPubKeys,
      final Map<String, Set<ArtifactSigner>> newProxySigners,
      final Map<String, Provenance> newProxyProvenance) {
    final Path keystoresPath = keystoreParameter.getKeystoresPath();
    if (!Files.isDirectory(keystoresPath) || !Files.isReadable(keystoresPath)) {
      // nothing generated yet is normal; losing a path that held proxy keys is a failure
      final boolean hadProxies =
          currentState.proxySigners.values().stream().anyMatch(proxies -> !proxies.isEmpty());
      if (hadProxies) {
        LOG.error(
            "Unable to read proxy keystores path {}; keeping previously loaded proxy keys",
            keystoresPath);
        retainAllProxySigners(currentState, consensusPubKeys, newProxySigners, newProxyProvenance);
      }
      return;
    }

    final Supplier<Optional<String>> password =
        Suppliers.memoize(() -> readProxyPassword(keystoreParameter.getKeystoresPasswordFile()));
    int retainedCount = 0;

    for (final String consensusPubKey : consensusPubKeys) {
      LOG.trace("Loading proxy signers for '{}' ...", consensusPubKey);
      final List<SignerSourceResult> dirResults = new ArrayList<>(2);
      loadProxyDir(
              keystoresPath,
              consensusPubKey,
              SECP256K1,
              password,
              SecpV3KeystoresBulkLoader::loadECDSAProxyKeystores)
          .ifPresent(dirResults::add);
      loadProxyDir(
              keystoresPath,
              consensusPubKey,
              BLS,
              password,
              BlsKeystoreBulkLoader::loadKeystoresUsingPassword)
          .ifPresent(dirResults::add);

      final Map<String, ArtifactSigner> proxies = new HashMap<>();
      for (final SignerSourceResult result : dirResults) {
        for (final SignerSourceResult.LoadedSigner loaded : result.signers()) {
          final String identifier = loaded.signer().getIdentifier();
          proxies.putIfAbsent(identifier, loaded.signer());
          newProxyProvenance.putIfAbsent(
              identifier, new Provenance(result.source(), loaded.file().orElse(null)));
        }
      }

      final long dirErrorCount =
          dirResults.stream().mapToLong(SignerSourceResult::errorCount).sum();
      for (final ArtifactSigner previous :
          currentState.proxySigners.getOrDefault(consensusPubKey, Set.of())) {
        final String identifier = previous.getIdentifier();
        if (proxies.containsKey(identifier)) {
          continue;
        }
        final Provenance provenance = currentState.proxyProvenance.get(identifier);
        if (failedToLoad(provenance, dirResults, dirErrorCount)) {
          proxies.put(identifier, previous);
          copyProvenance(currentState, identifier, newProxyProvenance);
          retainedCount++;
        }
      }

      if (!proxies.isEmpty()) {
        newProxySigners.put(consensusPubKey, Set.copyOf(proxies.values()));
      }
    }

    if (retainedCount > 0) {
      LOG.warn(
          "Retained {} previously loaded proxy key(s) whose directory or file failed to load",
          retainedCount);
    }
  }

  private static void retainAllProxySigners(
      final SignerState currentState,
      final Set<String> consensusPubKeys,
      final Map<String, Set<ArtifactSigner>> newProxySigners,
      final Map<String, Provenance> newProxyProvenance) {
    int retainedCount = 0;
    for (final String consensusPubKey : consensusPubKeys) {
      final Set<ArtifactSigner> previousProxies =
          currentState.proxySigners.getOrDefault(consensusPubKey, Set.of());
      if (!previousProxies.isEmpty()) {
        newProxySigners.put(consensusPubKey, previousProxies);
        previousProxies.forEach(
            proxy -> copyProvenance(currentState, proxy.getIdentifier(), newProxyProvenance));
        retainedCount += previousProxies.size();
      }
    }
    if (retainedCount > 0) {
      LOG.warn("Retained {} previously loaded proxy key(s)", retainedCount);
    }
  }

  private static void copyProvenance(
      final SignerState currentState,
      final String identifier,
      final Map<String, Provenance> newProxyProvenance) {
    final Provenance provenance = currentState.proxyProvenance.get(identifier);
    if (provenance != null) {
      newProxyProvenance.put(identifier, provenance);
    }
  }

  private static Optional<String> readProxyPassword(final Path passwordFile) {
    try {
      return Optional.of(Files.readString(passwordFile));
    } catch (final IOException e) {
      LOG.error(
          "Unable to read proxy keystores password file; keeping previously loaded proxy keys", e);
      return Optional.empty();
    }
  }

  /**
   * Loads the proxy keystores of one consensus key and key type. Returns empty when the directory
   * does not exist (no proxy keys). The result is named after the directory so that the provenance
   * of each proxy signer identifies it; an unreadable password file fails the directory as a whole.
   */
  private static Optional<SignerSourceResult> loadProxyDir(
      final Path keystoresPath,
      final String consensusPubKey,
      final KeyType keyType,
      final Supplier<Optional<String>> password,
      final BiFunction<Path, String, SignerSourceResult> loaderFunction) {
    final Path proxyDir = keystoresPath.resolve(consensusPubKey).resolve(keyType.name());
    if (!Files.exists(proxyDir)) {
      return Optional.empty();
    }
    final String source = proxyDir.toString();
    if (password.get().isEmpty()) {
      return Optional.of(SignerSourceResult.sourceFailure(source));
    }
    final SignerSourceResult result = loaderFunction.apply(proxyDir, password.get().get());
    return Optional.of(
        new SignerSourceResult(
            source,
            result.signers(),
            result.failedFiles(),
            result.errorCount(),
            result.sourceFailed()));
  }

  /**
   * Keeps the previously loaded signers that are missing from this load because their source failed
   * as a whole or their own file failed to load. Only the loaders can tell a deleted key from an
   * unreadable one, so removing such signers would disable validators in the slashing protection
   * database.
   */
  private static void retainSignersOfFailedSources(
      final SignerState currentState,
      final List<SignerSourceResult> results,
      final long errorCount,
      final Map<String, ArtifactSigner> newSigners,
      final Map<String, Provenance> newProvenance) {
    int retainedCount = 0;
    for (final Map.Entry<String, ArtifactSigner> previous : currentState.signers.entrySet()) {
      final String identifier = previous.getKey();
      if (newSigners.containsKey(identifier)) {
        continue;
      }
      final Provenance provenance = currentState.provenance.get(identifier);
      if (!failedToLoad(provenance, results, errorCount)) {
        continue;
      }
      newSigners.put(identifier, previous.getValue());
      if (provenance != null) {
        newProvenance.put(identifier, provenance);
      }
      retainedCount++;
    }

    if (retainedCount > 0) {
      LOG.warn(
          "Retained {} previously loaded key(s) whose source or file failed to load; a successful load of their source removes them",
          retainedCount);
    }
  }

  /**
   * A missing signer is kept when the load failed for its source as a whole or for the file it was
   * loaded from. A signer added at runtime (key manager API) has no provenance: nothing in the load
   * results can confirm its removal, so it is kept while any source reports errors.
   */
  private static boolean failedToLoad(
      final Provenance provenance, final List<SignerSourceResult> results, final long errorCount) {
    if (provenance == null) {
      return errorCount > 0;
    }
    return results.stream()
        .filter(result -> result.source().equals(provenance.source()))
        .anyMatch(
            result ->
                result.sourceFailed()
                    || (provenance.file() != null
                        && result.failedFiles().contains(provenance.file())));
  }
}
