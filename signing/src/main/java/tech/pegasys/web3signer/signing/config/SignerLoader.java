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

import tech.pegasys.web3signer.common.config.SignerLoaderConfig;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.config.SignerSourceResult.LoadedSigner;
import tech.pegasys.web3signer.signing.config.metadata.SigningMetadata;
import tech.pegasys.web3signer.signing.config.metadata.SigningMetadataException;
import tech.pegasys.web3signer.signing.config.metadata.parser.SignerParser;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The SignerLoader loads metadata files and converts them to ArtifactSigners using a smart caching
 * mechanism. This class maintains a cache of loaded signers and automatically handles file
 * additions and deletions. It leverages Java virtual threads for efficient parallel processing of
 * large file sets.
 *
 * <p>Processing strategies:
 *
 * <ul>
 *   <li>Sequential: For small batches (below sequential threshold, default 100 files)
 *   <li>Parallel with virtual threads: For larger batches, processed in configurable batch sizes
 * </ul>
 *
 * <p>This class implements {@link Closeable} for proper resource management. The {@link #close()}
 * method is idempotent as required by the Closeable contract.
 *
 * <p>This class is thread-safe and uses volatile references with immutable maps for lock-free
 * reads. All write operations are atomic through copy-on-write semantics.
 */
public class SignerLoader implements Closeable {
  private static final Logger LOG = LogManager.getLogger();
  private static final Set<String> CONFIG_FILE_EXTENSIONS = Set.of("yaml", "yml");
  private static final String SOURCE_NAME = "key config files";

  private volatile Map<String, CachedSignerData> cachedArtifactSigners = Collections.emptyMap();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /** Holds cached signer data along with file metadata for cache invalidation. */
  private record CachedSignerData(
      String filePath, FileTime lastModifiedTime, Set<ArtifactSigner> signers) {}

  /**
   * Result of loading signers from configuration files.
   *
   * @param loadedSigners map of file paths to their loaded artifact signers
   * @param failedPaths paths of files that were present but failed to load; each counts as one
   *     error
   */
  @VisibleForTesting
  record LoadResult(Map<String, Set<ArtifactSigner>> loadedSigners, Set<String> failedPaths) {
    int errorCount() {
      return failedPaths.size();
    }
  }

  private final SignerLoaderConfig config;
  private final ExecutorService virtualThreadExecutor;

  public SignerLoader(final SignerLoaderConfig config) {
    this.config = config;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Load ArtifactSigners for metadata files. This method maintains a cache of loaded signers and
   * intelligently handles file changes:
   *
   * <ul>
   *   <li>New files are processed and added to cache
   *   <li>Modified files (based on last modified time) are reprocessed
   *   <li>Deleted files are removed from cache
   *   <li>Unchanged files remain in cache without reprocessing
   * </ul>
   *
   * <p>The returned result names the file each signer was loaded from and lists the files that
   * failed, so that {@link DefaultArtifactSignerProvider} can keep the signers of files that failed
   * while removing the signers of files that were deleted.
   *
   * @param signerParser An implementation of SignerParser to parse the metadata files
   * @return the loaded signers with the file each one came from, the failed files and the error
   *     count
   * @throws IllegalStateException if this SignerLoader has been closed
   */
  public SignerSourceResult load(final SignerParser signerParser) {
    if (closed.get()) {
      throw new IllegalStateException("SignerLoader instance has been closed");
    }
    LOG.info("Loading signer configuration metadata files from {}", config.configsDirectory());
    final Instant loadStartTime = Instant.now();

    // Get all metadata file paths from the config directory
    final Map<String, FileTime> availableFilesWithTime;
    try {
      availableFilesWithTime = getMetadataConfigFilesWithTime();
    } catch (final IOException e) {
      LOG.error("Unable to access the supplied key directory", e);
      return SignerSourceResult.sourceFailure(SOURCE_NAME);
    }

    // Get new cache by removing entries that doesn't exist or has modified time
    final Map<String, CachedSignerData> newCache = getNewCacheMap(availableFilesWithTime);

    // Find files to process (new + modified)
    final Set<String> newFilesToProcess =
        getNewFilesToProcess(availableFilesWithTime.keySet(), newCache.keySet());
    LOG.info(
        "Processing {} metadata files. Cached paths: {}",
        newFilesToProcess.size(),
        newCache.size());

    // load new signers
    final LoadResult loadResult = loadNewSigners(newFilesToProcess, signerParser);

    // Add newly loaded signers to cache with their modification times
    loadResult.loadedSigners.forEach(
        (pathStr, signers) -> {
          FileTime modTime = availableFilesWithTime.get(pathStr);
          if (modTime == null) {
            LOG.warn("No modification time found for {}, using current time", pathStr);
            modTime = FileTime.from(Instant.now());
          }
          newCache.put(pathStr, new CachedSignerData(pathStr, modTime, signers));
          LOG.trace("Added {} signers from {}", signers.size(), pathStr);
        });

    // Update the volatile reference with the new immutable map
    cachedArtifactSigners = Map.copyOf(newCache);

    // a signer can be defined by more than one file: keep the first occurrence
    final Map<String, LoadedSigner> uniqueSigners = new LinkedHashMap<>();
    cachedArtifactSigners
        .values()
        .forEach(
            cached ->
                cached
                    .signers()
                    .forEach(
                        signer ->
                            uniqueSigners.putIfAbsent(
                                signer.getIdentifier(),
                                new LoadedSigner(signer, Optional.of(cached.filePath())))));
    final List<LoadedSigner> loadedSigners = List.copyOf(uniqueSigners.values());

    LOG.info(
        "Total Artifact Signers loaded via configuration files: {}\nTotal Paths cached: {}, Error count: {}\nTime Taken: {}.",
        loadedSigners.size(),
        cachedArtifactSigners.size(),
        loadResult.errorCount(),
        calculateTimeTaken(loadStartTime).orElse("unknown duration"));

    return new SignerSourceResult(
        SOURCE_NAME, loadedSigners, loadResult.failedPaths(), loadResult.errorCount(), false);
  }

  /**
   * Identifies files that need to be processed by computing the set difference between available
   * files and unchanged cached files. The result includes both new files (never cached) and
   * modified files (cached but with different timestamps).
   *
   * @param availableFiles all files currently present in the directory
   * @param unchangedCachedFiles files in cache that haven't been modified
   * @return set of file paths that need to be processed (new + modified files)
   */
  private Set<String> getNewFilesToProcess(
      final Set<String> availableFiles, final Set<String> unchangedCachedFiles) {
    final Set<String> filesToProcess = new HashSet<>(availableFiles);
    filesToProcess.removeAll(unchangedCachedFiles);

    // filesToProcess now contains:
    // 1. New files (were never in cache)
    // 2. Modified files (were in cache but have different timestamp)
    return filesToProcess;
  }

  /**
   * Creates a new cache map containing only files that still exist on the filesystem and haven't
   * been modified since last cached. Files are retained in cache only if:
   *
   * <ol>
   *   <li>They still exist in the filesystem
   *   <li>Their last modified time hasn't changed
   * </ol>
   *
   * Modified or deleted files are excluded from the returned cache and will be reprocessed or
   * removed respectively.
   *
   * @param currentFilesWithTime map of current file paths to their last modified times
   * @return new cache map containing only unchanged files
   */
  private Map<String, CachedSignerData> getNewCacheMap(
      final Map<String, FileTime> currentFilesWithTime) {

    final Map<String, CachedSignerData> currentCacheSnapshot = Map.copyOf(cachedArtifactSigners);
    final Map<String, CachedSignerData> newCache = new HashMap<>();

    // Iterate cached files
    for (Map.Entry<String, CachedSignerData> entry : currentCacheSnapshot.entrySet()) {
      final String filePath = entry.getKey();
      final CachedSignerData cachedData = entry.getValue();

      // only add the files which are there on file system and modified time not changed
      if (currentFilesWithTime.containsKey(filePath)) {
        final FileTime currentModTime = currentFilesWithTime.get(filePath);

        // Keep in cache only if unchanged
        if (currentModTime.compareTo(cachedData.lastModifiedTime()) == 0) {
          newCache.put(filePath, cachedData);
        } else {
          LOG.trace("File modified, will reprocess: {}", filePath);
        }
      } else {
        LOG.trace("File deleted: {}", filePath);
      }
    }

    return newCache; // Contains ONLY unchanged cached files
  }

  /**
   * Loads new signers from the specified files using the appropriate processing strategy based on
   * file count and configuration.
   *
   * @param newFilesToProcess files that need to be loaded
   * @param signerParser parser to convert file content to signers
   * @return pair of loaded signers map and error count
   */
  @VisibleForTesting
  LoadResult loadNewSigners(final Set<String> newFilesToProcess, final SignerParser signerParser) {

    final AtomicLong configFilesHandled = new AtomicLong(0);
    final int totalFiles = newFilesToProcess.size();

    if (totalFiles == 0) {
      return new LoadResult(Map.of(), Set.of());
    }

    // Sequential processing for small batches or when parallel is disabled
    if (!config.parallelProcess() || totalFiles < config.sequentialThreshold()) {
      LOG.info("Processing {} files sequentially", totalFiles);
      return processSequentially(newFilesToProcess, signerParser, configFilesHandled, totalFiles);
    }

    // Parallel processing with batches
    LOG.info("Processing {} files in parallel with batch size {}", totalFiles, config.batchSize());
    return processInBatches(newFilesToProcess, signerParser, configFilesHandled, totalFiles);
  }

  /**
   * Processes files in parallel using virtual threads, subdivided into batches.
   *
   * @param filesToProcess set of file paths to process
   * @param signerParser parser for converting file content
   * @param configFilesHandled atomic counter for progress tracking
   * @param totalFiles total number of files for progress reporting
   * @return LoadResult with all loaded signers and total error count
   */
  @VisibleForTesting
  LoadResult processInBatches(
      final Set<String> filesToProcess,
      final SignerParser signerParser,
      final AtomicLong configFilesHandled,
      final int totalFiles) {

    final Map<String, Set<ArtifactSigner>> allLoadedSigners = new HashMap<>();
    final Set<String> failedPaths = new HashSet<>();
    final List<String> fileList = new ArrayList<>(filesToProcess);
    boolean stoppedEarly = false;

    for (int batchStart = 0; batchStart < fileList.size(); batchStart += config.batchSize()) {
      if (closed.get()) {
        LOG.warn("SignerLoader closed during batch processing");
        stoppedEarly = true;
        break;
      }
      final int batchEnd = Math.min(batchStart + config.batchSize(), fileList.size());
      final List<String> batch = fileList.subList(batchStart, batchEnd);

      if (totalFiles > config.batchSize()) {
        LOG.info("Processing batch {}-{} of {} files", batchStart + 1, batchEnd, totalFiles);
      }

      // Submit all tasks in this batch
      final List<Future<LoadResult>> futures = new ArrayList<>();
      for (String pathStr : batch) {
        futures.add(
            virtualThreadExecutor.submit(
                () -> processFile(pathStr, signerParser, configFilesHandled, totalFiles)));
      }

      try {
        // Collect results from this batch
        final LoadResult batchResult = collectBatchResults(futures, batch);

        // Accumulate into overall results
        allLoadedSigners.putAll(batchResult.loadedSigners());
        failedPaths.addAll(batchResult.failedPaths());

      } catch (final InterruptedException e) {
        // Interrupted while collecting batch results
        LOG.warn("Batch processing interrupted, returning partial results");
        // Thread interrupt status already restored by collectBatchResults
        stoppedEarly = true;
        break; // Stop processing further batches
      }
    }

    if (stoppedEarly) {
      // files that were not processed are not deleted: report them as failed so that the signers
      // previously loaded from them are kept
      fileList.stream()
          .filter(pathStr -> !allLoadedSigners.containsKey(pathStr))
          .forEach(failedPaths::add);
    }

    return new LoadResult(allLoadedSigners, failedPaths);
  }

  /**
   * Collects results from a batch of futures, applying individual timeout to each task.
   *
   * <p>If interrupted while waiting for a future, this method:
   *
   * <ol>
   *   <li>Restores the thread's interrupt status
   *   <li>Cancels all remaining unprocessed futures
   *   <li>Propagates the InterruptedException to the caller
   * </ol>
   *
   * @param futures list of submitted futures to collect results from
   * @param batch corresponding file paths for logging purposes
   * @return LoadResult containing all successfully loaded signers and total error count
   * @throws InterruptedException if interrupted while waiting for any future to complete
   */
  @VisibleForTesting
  LoadResult collectBatchResults(final List<Future<LoadResult>> futures, final List<String> batch)
      throws InterruptedException {

    final Map<String, Set<ArtifactSigner>> allLoadedSigners = new HashMap<>();
    final Set<String> failedPaths = new HashSet<>();

    for (int i = 0; i < futures.size(); i++) {
      final Future<LoadResult> future = futures.get(i);
      final String filePath = batch.get(i);

      try {
        final LoadResult result = future.get(config.taskTimeoutSeconds(), TimeUnit.SECONDS);

        // Accumulate results
        allLoadedSigners.putAll(result.loadedSigners);
        failedPaths.addAll(result.failedPaths());
      } catch (final TimeoutException e) {
        LOG.error("Task timed out after {} seconds: {}", config.taskTimeoutSeconds(), filePath);
        future.cancel(true);
        failedPaths.add(filePath);
      } catch (final InterruptedException e) {
        // Restore interrupt status
        Thread.currentThread().interrupt();

        LOG.error("Interrupted while collecting batch results");

        // Cancel all remaining futures to free resources
        cancelRemainingFutures(futures, i);
        throw e; // propagate interruption to caller
      } catch (final ExecutionException e) {
        LOG.warn("Task execution failed: {}", filePath, e);
        failedPaths.add(filePath);
      } catch (final CancellationException e) {
        // task was intentionally canceled - not an error
        LOG.debug("Task was canceled: {}", filePath);
      }
    }
    return new LoadResult(allLoadedSigners, failedPaths);
  }

  /** Cancels all futures starting from the given index. */
  private void cancelRemainingFutures(
      final List<Future<LoadResult>> futures, final int startIndex) {
    for (int j = startIndex; j < futures.size(); j++) {
      futures.get(j).cancel(true);
    }
  }

  /**
   * Processes files sequentially without parallelization. Used for small batches or when parallel
   * processing is disabled.
   *
   * @param newFilesToProcess files to process
   * @param signerParser parser for converting metadata to signers
   * @param configFilesHandled counter for processed files
   * @param totalFiles total number of files for progress reporting
   * @return LoadResult with loaded signers and error count
   */
  @VisibleForTesting
  LoadResult processSequentially(
      final Set<String> newFilesToProcess,
      final SignerParser signerParser,
      final AtomicLong configFilesHandled,
      final int totalFiles) {

    final Map<String, Set<ArtifactSigner>> loadedArtSigners = new HashMap<>();
    final Set<String> failedPaths = new HashSet<>();

    for (String pathStr : newFilesToProcess) {
      final LoadResult result = processFile(pathStr, signerParser, configFilesHandled, totalFiles);
      loadedArtSigners.putAll(result.loadedSigners);
      failedPaths.addAll(result.failedPaths());
    }

    return new LoadResult(loadedArtSigners, failedPaths);
  }

  /**
   * Processes a single metadata file and converts it to ArtifactSigners.
   *
   * <p>This method handles the complete lifecycle of processing a signer configuration file:
   *
   * <ol>
   *   <li><b>File Reading (IO-bound):</b> Reads the file content from disk using NIO operations
   *   <li><b>Metadata Parsing (Mixed IO/CPU):</b> Parses the YAML/YML content into SigningMetadata
   *       objects
   *   <li><b>Signer Creation (CPU-bound):</b> Performs decryption and creates ArtifactSigner
   *       instances
   * </ol>
   *
   * <p>The method includes comprehensive error handling and thread interruption checks at strategic
   * points to ensure responsive cancellation when running in virtual threads. Interruption checks
   * are placed:
   *
   * <ul>
   *   <li>Before starting any work (early exit optimization)
   *   <li>After IO operations (NIO channels may be affected by interruption)
   *   <li>Before CPU-intensive decryption operations (which can take up to a minute)
   * </ul>
   *
   * <p><b>Thread Safety:</b> This method is thread-safe and can be called concurrently from
   * multiple virtual threads. The shared counters use atomic operations for thread-safe updates.
   *
   * <p><b>Error Handling:</b> All errors are logged with appropriate detail levels and increment
   * the error counter. The method returns {@code null} for any processing failure rather than
   * propagating exceptions, allowing batch processing to continue with other files.
   *
   * @param pathStr absolute normalized path to the metadata file to process
   * @param signerParser parser implementation for converting metadata to ArtifactSigners
   * @param configFilesHandled atomic counter tracking total files processed (for progress
   *     reporting)
   * @param totalFiles total number of files being processed in this batch (for progress
   *     calculation)
   * @return a Map.Entry with the file path as key and immutable Set of ArtifactSigners as value, or
   *     {@code null} if processing failed or was interrupted
   * @implNote The returned Set of ArtifactSigners is made immutable using {@code Set.copyOf()} to
   *     ensure thread safety when cached. The decryption step (Step 3) is the most time-consuming
   *     operation and may involve HSM operations, encrypted key material decryption, or remote key
   *     vault access.
   */
  @VisibleForTesting
  LoadResult processFile(
      final String pathStr,
      final SignerParser signerParser,
      final AtomicLong configFilesHandled,
      final int totalFiles) {
    // Interrupted (cancelled or shutting down) files are reported as failed, not as deleted
    final Set<String> interrupted = Set.of(pathStr);
    final Set<String> noFailures = Set.of();

    // Check interruption at the beginning
    if (Thread.currentThread().isInterrupted()) {
      LOG.debug("File processing interrupted before start: {}", pathStr);
      return new LoadResult(Map.of(), interrupted);
    }

    reportProgress(configFilesHandled, totalFiles);

    try {
      // Step 1: File reading (IO-bound)
      final Path filePath = Path.of(pathStr);
      final String content = Files.readString(filePath, StandardCharsets.UTF_8);

      // Check interruption after IO operation
      if (Thread.currentThread().isInterrupted()) {
        LOG.debug("File processing interrupted after reading: {}", pathStr);
        return new LoadResult(Map.of(), interrupted);
      }

      // Step 2: Parse metadata (mixed IO/CPU)
      final List<SigningMetadata> signingMetadata;
      try {
        signingMetadata = signerParser.readSigningMetadata(content);
      } catch (final SigningMetadataException e) {
        LOG.error(
            "Error parsing metadata file {} to signing metadata: {}",
            pathStr,
            ExceptionUtils.getRootCauseMessage(e));
        return new LoadResult(Map.of(), Set.of(pathStr));
      }

      // Check interruption before expensive decryption operation
      if (Thread.currentThread().isInterrupted()) {
        LOG.debug("File processing interrupted before decryption: {}", pathStr);
        return new LoadResult(Map.of(), interrupted);
      }

      // Step 3: Decryption and conversion (CPU-bound, can take up to a minute)
      final Set<ArtifactSigner> artifactSigners;
      try {
        artifactSigners = Set.copyOf(signerParser.parse(signingMetadata));
      } catch (final SigningMetadataException e) {
        LOG.error(
            "Error converting signing metadata to Artifact Signer for file {}: {}",
            pathStr,
            ExceptionUtils.getRootCauseMessage(e));
        return new LoadResult(Map.of(), Set.of(pathStr));
      }

      LOG.trace("Successfully processed file {} with {} signers", pathStr, artifactSigners.size());
      return new LoadResult(Map.of(pathStr, artifactSigners), noFailures);
    } catch (final IOException e) {
      // Check if IOException was caused by interruption
      if (Thread.currentThread().isInterrupted()) {
        LOG.debug("File processing interrupted during IO: {}", pathStr);
        return new LoadResult(Map.of(), interrupted);
      }
      LOG.error("Error reading metadata config file: {}", pathStr, e);
      return new LoadResult(Map.of(), Set.of(pathStr));
    } catch (final Exception e) {
      // Check if exception is due to thread interruption (timeout/cancellation)
      if (Thread.currentThread().isInterrupted()
          || e instanceof InterruptedException
          || (e.getCause() != null && e.getCause() instanceof InterruptedException)) {
        LOG.debug("File processing cancelled: {}", pathStr);
        return new LoadResult(Map.of(), interrupted);
      }
      LOG.error("Unexpected error processing file: {}", pathStr, e);
      return new LoadResult(Map.of(), Set.of(pathStr));
    }
  }

  /**
   * Reports processing progress at dynamic intervals based on total file count. Ensures
   * approximately 100 progress reports regardless of file count, with a minimum interval of 10
   * files.
   *
   * @param processed atomic counter of processed files
   * @param total total number of files being processed
   */
  private void reportProgress(final AtomicLong processed, final int total) {
    long count = processed.incrementAndGet();
    // Report ~100 times max, or every 10 files minimum
    int interval = Math.max(10, total / 100);
    if (count % interval == 0 || count == total) {
      final int percentage = (int) Math.round((count * 100.0) / total);
      LOG.info("Processed {}/{} files ({}%)", count, total, percentage);
    }
  }

  /**
   * Closes this SignerLoader and releases resources. This method is idempotent - calling it
   * multiple times has no additional effect.
   *
   * @throws IOException if an I/O error occurs during shutdown
   */
  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }

    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
          LOG.warn("Executor did not terminate in 5 minutes, forcing shutdown");
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        LOG.error("Interrupted while waiting for executor shutdown", e);
        virtualThreadExecutor.shutdownNow();
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted during close", e);
      }
    }

    // Clear cache to release memory - AFTER executor is shut down
    cachedArtifactSigners = Collections.emptyMap();
  }

  /**
   * Get all metadata config file paths with their last modified times from the directory.
   *
   * @return A map of normalized path strings to their last modified times
   * @throws IOException If there is an error reading the config directory
   */
  private Map<String, FileTime> getMetadataConfigFilesWithTime() throws IOException {
    if (!Files.exists(config.configsDirectory())) {
      throw new FileNotFoundException("Config directory does not exist");
    }

    if (!Files.isDirectory(config.configsDirectory())) {
      throw new IOException("Path is not a directory: " + config.configsDirectory());
    }

    try (final Stream<Path> fileStream = Files.list(config.configsDirectory())) {
      return fileStream
          .filter(SignerLoader::validFileExtension)
          .collect(
              Collectors.toMap(
                  path -> path.toAbsolutePath().normalize().toString(),
                  path -> {
                    try {
                      return Files.getLastModifiedTime(path);
                    } catch (IOException e) {
                      LOG.warn("Could not get last modified time for {}, using current time", path);
                      return FileTime.from(Instant.now());
                    }
                  }));
    }
  }

  @VisibleForTesting
  static Optional<String> calculateTimeTaken(final Instant start) {
    final Instant now = Instant.now();
    final long timeTaken = Duration.between(start, now).toMillis();
    if (timeTaken < 0) {
      LOG.warn("System Clock returned time in past. Start: {}, Now: {}.", start, now);
      return Optional.empty();
    }
    return Optional.of(DurationFormatUtils.formatDurationHMS(timeTaken));
  }

  /**
   * Validates if a file has an acceptable extension for processing. Filters out hidden files and
   * only accepts .yaml and .yml extensions.
   *
   * @param filename path to validate
   * @return true if file should be processed, false otherwise
   */
  private static boolean validFileExtension(final Path filename) {
    final boolean isHidden = filename.toFile().isHidden();
    final String extension = FilenameUtils.getExtension(filename.toString());
    return !isHidden && CONFIG_FILE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
  }
}
