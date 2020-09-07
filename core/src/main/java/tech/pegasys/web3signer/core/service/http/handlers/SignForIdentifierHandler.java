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
package tech.pegasys.web3signer.core.service.http.handlers;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static tech.pegasys.web3signer.core.service.http.handlers.ContentTypes.TEXT_PLAIN_UTF_8;
import static tech.pegasys.web3signer.core.service.operations.IdentifierUtils.normaliseIdentifier;

import tech.pegasys.web3signer.core.metrics.Web3SignerMetricCategory;
import tech.pegasys.web3signer.core.service.http.SignRequestBody;
import tech.pegasys.web3signer.core.service.operations.SignerForIdentifier;
import tech.pegasys.web3signer.slashingprotection.SlashingProtection;

import java.util.Optional;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.api.RequestParameter;
import io.vertx.ext.web.api.RequestParameters;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer.TimingContext;

public class SignForIdentifierHandler implements Handler<RoutingContext> {

  private static final Logger LOG = LogManager.getLogger();

  private final SignerForIdentifier<?> signerForIdentifier;
  private final Optional<SlashingProtection> slashingProtection;

  private final Counter malformedRequestCounter;
  private final OperationTimer signingTimer;

  public SignForIdentifierHandler(
      final SignerForIdentifier<?> signerForIdentifier,
      final MetricsSystem metrics,
      final String metricsPrefix,
      final SlashingProtection slashingProtection) {
    this.signerForIdentifier = signerForIdentifier;
    this.slashingProtection = Optional.ofNullable(slashingProtection);

    malformedRequestCounter =
        metrics.createCounter(
            Web3SignerMetricCategory.HTTP,
            metricsPrefix + "_malformed_request_count",
            "Number of requests received which had illegally formatted body.");
    signingTimer =
        metrics.createTimer(
            Web3SignerMetricCategory.SIGNING,
            metricsPrefix + "_signing_duration",
            "Duration of a signing event");
  }

  @Override
  public void handle(final RoutingContext routingContext) {
    try (final TimingContext ignored = signingTimer.startTimer()) {
      final RequestParameters params = routingContext.get("parsedParameters");
      final String identifier = params.pathParameter("identifier").toString();
      final SignRequestBody requestBody;
      try {
        requestBody = deserialiseBody(params);
      } catch (final IllegalArgumentException e) {
        malformedRequestCounter.inc();
        routingContext.fail(400);
        return;
      }

      final String normalisedIdentifier = normaliseIdentifier(identifier);
      signerForIdentifier
          .sign(normalisedIdentifier, requestBody.getDataToSign())
          .ifPresentOrElse(
              signature -> {
                if (isSigningLegal(normalisedIdentifier, requestBody)) {
                  routingContext
                      .response()
                      .putHeader(CONTENT_TYPE, TEXT_PLAIN_UTF_8)
                      .end(signature);
                }
              },
              () -> {
                LOG.trace("Unsuitable handler for {}, invoking next handler", identifier);
                routingContext.next();
              });
    }
  }

  private boolean isSigningLegal(final String identifier, final SignRequestBody requestBody) {
    if (slashingProtection.isEmpty()) {
      return true;
    }

    if (requestBody.getArtifactType().equals("Block")) {
      return slashingProtection.get().maySignBlock(identifier, requestBody.getBlockSlot());
    } else if (requestBody.getArtifactType().equals("Attestation")) {
      return slashingProtection
          .get()
          .maySignAttestation(
              identifier, requestBody.getSourceEpoch(), requestBody.getTargetEpoch());
    } else {
      throw new RuntimeException("ILLEGAL SIGN TYPE");
    }
  }

  private SignRequestBody deserialiseBody(final RequestParameters params) {
    final RequestParameter body = params.body();
    final JsonObject jsonObject = body.getJsonObject();
    return jsonObject.mapTo(SignRequestBody.class); // what ObjectMapper does this use?!
  }
}
