/*
 * Copyright 2022 ConsenSys AG.
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
package tech.pegasys.web3signer.signing.bulkloading;

import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.web3signer.bls.keystore.KeyStore;
import tech.pegasys.web3signer.bls.keystore.KeyStoreLoader;
import tech.pegasys.web3signer.bls.keystore.KeyStoreValidationException;
import tech.pegasys.web3signer.bls.keystore.model.KeyStoreData;
import tech.pegasys.web3signer.signing.ArtifactSigner;
import tech.pegasys.web3signer.signing.BlsArtifactSigner;
import tech.pegasys.web3signer.signing.config.SignerSourceResult;
import tech.pegasys.web3signer.signing.config.SignerSourceResult.LoadedSigner;
import tech.pegasys.web3signer.signing.config.metadata.SignerOrigin;
import tech.pegasys.web3signer.signing.secp256k1.util.JsonFilesUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bulk-loads BLS keystores from a directory. The result names the keystore file of every loaded
 * signer and lists the keystore files that failed to load, so that {@link
 * tech.pegasys.web3signer.signing.config.DefaultArtifactSignerProvider} can keep the signers of
 * failed files while dropping the signers of deleted files.
 */
public class BlsKeystoreBulkLoader {
  private static final Logger LOG = LogManager.getLogger();
  private static final String SOURCE_NAME = "bls keystores";

  public static SignerSourceResult loadKeystoresUsingPasswordDir(
      final Path keystoresDirectory, final Path passwordsDirectory) {
    final List<Path> keystoreFiles;
    try {
      keystoreFiles = JsonFilesUtil.loadJsonExtPaths(keystoresDirectory);
    } catch (final IOException e) {
      LOG.error("Error reading keystore files", e);
      return SignerSourceResult.sourceFailure(SOURCE_NAME);
    }

    return loadKeystores(
        keystoreFiles,
        keystoreFile ->
            createSignerForKeystore(
                keystoreFile,
                keystorePassword ->
                    Files.readString(passwordsDirectory.resolve(keystorePassword))));
  }

  public static SignerSourceResult loadKeystoresUsingPasswordFile(
      final Path keystoresDirectory, final Path passwordFile) {
    final String password;
    try {
      password = Files.readString(passwordFile);
    } catch (final IOException e) {
      LOG.error("Unable to read password file", e);
      return SignerSourceResult.sourceFailure(SOURCE_NAME);
    }

    return loadKeystoresUsingPassword(keystoresDirectory, password);
  }

  /**
   * Loads every keystore in the directory with the same, already read, password. Used for proxy
   * keystores, whose password file is read once per load for all proxy directories.
   */
  public static SignerSourceResult loadKeystoresUsingPassword(
      final Path keystoresDirectory, final String password) {
    final List<Path> keystoreFiles;
    try {
      keystoreFiles = JsonFilesUtil.loadJsonExtPaths(keystoresDirectory);
    } catch (final IOException e) {
      LOG.error("Error reading keystore files", e);
      return SignerSourceResult.sourceFailure(SOURCE_NAME);
    }

    return loadKeystores(
        keystoreFiles, keystoreFile -> createSignerForKeystore(keystoreFile, ignored -> password));
  }

  private static SignerSourceResult loadKeystores(
      final List<Path> keystoreFiles, final Function<Path, Optional<ArtifactSigner>> loadFile) {
    final ConcurrentLinkedQueue<LoadedSigner> signers = new ConcurrentLinkedQueue<>();
    final Set<String> failedFiles = ConcurrentHashMap.newKeySet();

    keystoreFiles.parallelStream()
        .forEach(
            keystoreFile -> {
              final Optional<ArtifactSigner> signer = loadFile.apply(keystoreFile);
              if (signer.isPresent()) {
                signers.add(new LoadedSigner(signer.get(), Optional.of(keystoreFile.toString())));
              } else {
                failedFiles.add(keystoreFile.toString());
              }
            });

    return new SignerSourceResult(
        SOURCE_NAME, List.copyOf(signers), failedFiles, failedFiles.size(), false);
  }

  private static Optional<ArtifactSigner> createSignerForKeystore(
      final Path keystoreFile, final PasswordReader passwordReader) {
    try {
      LOG.debug("Loading keystore {}", keystoreFile);
      final KeyStoreData keyStoreData = KeyStoreLoader.loadFromFile(keystoreFile.toUri());
      final String fileNameWithoutExt =
          FilenameUtils.removeExtension(keystoreFile.getFileName().toString());
      final String password = passwordReader.readPassword(fileNameWithoutExt + ".txt");
      final BLSKeyPair keyPair = KeyStore.decrypt(password, keyStoreData);
      return Optional.of(new BlsArtifactSigner(keyPair, SignerOrigin.FILE_KEYSTORE));
    } catch (final KeyStoreValidationException | IOException e) {
      LOG.error("Keystore could not be loaded {}", keystoreFile, e);
      return Optional.empty();
    }
  }
}
