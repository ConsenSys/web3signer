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
package tech.pegasys.web3signer.slashingprotection.validator;

import static org.jdbi.v3.core.transaction.TransactionIsolationLevel.READ_COMMITTED;

import tech.pegasys.web3signer.slashingprotection.dao.MetadataDao;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeExecutor;
import net.jodah.failsafe.RetryPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementException;

public class GenesisValidatorRootValidator {

  private static final Logger LOG = LogManager.getLogger();
  private final Jdbi jdbi;
  private final MetadataDao metadataDao;
  private final FailsafeExecutor<Object> failsafeExecutor;

  private final Cache<String, Bytes32> gvrCache = CacheBuilder.newBuilder().maximumSize(1).build();

  public GenesisValidatorRootValidator(final Jdbi jdbi, final MetadataDao metadataDao) {
    this.jdbi = jdbi;
    this.metadataDao = metadataDao;
    this.failsafeExecutor =
        Failsafe.with(new RetryPolicy<>().handle(StatementException.class).withMaxRetries(1));
  }

  public void initialiseGenesisValidatorsRoot(final Bytes32 genesisValidatorsRoot) {
    final Bytes32 databaseGvr = insertAndCacheGVR(genesisValidatorsRoot);
    if (!databaseGvr.equals(genesisValidatorsRoot)) {
      throw new IllegalStateException(
          String.format(
              "Genesis validators root %s of the configured network does not match genesis validators root %s in the slashing protection database",
              genesisValidatorsRoot, databaseGvr));
    }
  }

  public boolean checkGenesisValidatorsRootAndInsertIfEmpty(final Bytes32 genesisValidatorsRoot) {
    var cachedGVR = insertAndCacheGVR(genesisValidatorsRoot);

    if (Objects.equals(cachedGVR, genesisValidatorsRoot)) {
      return true;
    } else {
      LOG.warn(
          "Supplied genesis validators root {} does not match value in database",
          genesisValidatorsRoot);
      return false;
    }
  }

  private Bytes32 insertAndCacheGVR(final Bytes32 genesisValidatorsRoot) {
    try {
      return gvrCache.get(
          "gvr",
          () -> {
            LOG.debug("Cached GVR not found, fetching from database");
            return failsafeExecutor.get(
                () ->
                    jdbi.inTransaction(
                        READ_COMMITTED,
                        handle -> findAndInsertIfNotExists(handle, genesisValidatorsRoot)));
          });
    } catch (final ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  public boolean genesisValidatorRootExists() {
    return failsafeExecutor.get(
        () ->
            jdbi.inTransaction(
                READ_COMMITTED,
                handle -> metadataDao.findGenesisValidatorsRoot(handle).isPresent()));
  }

  private Bytes32 findAndInsertIfNotExists(
      final Handle handle, final Bytes32 genesisValidatorsRoot) {
    final Optional<Bytes32> dbGvr = metadataDao.findGenesisValidatorsRoot(handle);
    if (dbGvr.isPresent()) {
      return dbGvr.get();
    } else {
      metadataDao.insertGenesisValidatorsRoot(handle, genesisValidatorsRoot);
      return genesisValidatorsRoot;
    }
  }
}
