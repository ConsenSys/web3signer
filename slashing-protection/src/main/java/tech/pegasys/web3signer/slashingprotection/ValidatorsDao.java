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
package tech.pegasys.web3signer.slashingprotection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

public class ValidatorsDao {
  private static final Logger LOG = LogManager.getLogger();
  private static final String SELECT_VALIDATOR = "SELECT id FROM validators WHERE public_key = ?";
  private static final String INSERT_VALIDATOR = "INSERT INTO validators (public_key) VALUES (?)";
  private final Supplier<Connection> connectionSupplier;

  public ValidatorsDao(final Supplier<Connection> connectionSupplier) {
    this.connectionSupplier = connectionSupplier;
  }

  public void registerValidators(final Collection<String> validators) {
    final Connection connection = connectionSupplier.get();
    try {
      connection.setAutoCommit(false);
      final PreparedStatement insertStatement = connection.prepareStatement(INSERT_VALIDATOR);

      for (String validator : validators) {
        final byte[] publicKeyBytes = Bytes.fromHexString(validator).toArrayUnsafe();
        final Optional<Long> validatorId = retrieveValidatorId(connection, publicKeyBytes);
        if (validatorId.isEmpty()) {
          insertStatement.setBytes(1, publicKeyBytes);
          insertStatement.execute();
        }
      }
      connection.commit();
    } catch (SQLException e) {
      LOG.error("Failed registering validators. Check slashing database is correctly setup.", e);
      throw new IllegalStateException("Failed registering validators", e);
    }
  }

  private Optional<Long> retrieveValidatorId(
      final Connection connection, final byte[] publicKeyBytes) throws SQLException {
    final PreparedStatement selectStatement = connection.prepareStatement(SELECT_VALIDATOR);
    selectStatement.setBytes(1, publicKeyBytes);
    final ResultSet resultSet = selectStatement.executeQuery();
    final List<Long> validatorIds = new ArrayList<>();
    while (resultSet.next()) {
      final long id = resultSet.getLong(1);
      validatorIds.add(id);
    }
    if (validatorIds.size() > 1) {
      throw new IllegalStateException(
          "Invalid validators table, more than one validator public key registered.");
    }
    return validatorIds.size() == 0 ? Optional.empty() : Optional.of(validatorIds.get(0));
  }
}
