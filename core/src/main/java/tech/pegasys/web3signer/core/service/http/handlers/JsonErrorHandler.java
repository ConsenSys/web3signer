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
package tech.pegasys.web3signer.core.service.http.handlers;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static tech.pegasys.web3signer.core.service.http.handlers.ContentTypes.JSON_UTF_8;

import java.util.Optional;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Router level error handler. Renders every routing failure that no route specific failure handler
 * has answered as a JSON {@code ErrorResponse} ({@code {"code": <status>, "message": "<http reason
 * phrase or ErrorResponseException message>"}}), instead of Vert.x's default plain text reason
 * phrase / HTML 404 page. Also records the failure in the log.
 */
public class JsonErrorHandler implements Handler<RoutingContext> {

  private static final Logger LOG = LogManager.getLogger();
  private static final int DEFAULT_ERROR_STATUS_CODE = 500;

  @Override
  public void handle(final RoutingContext context) {
    final int statusCode =
        context.statusCode() > 0 ? context.statusCode() : DEFAULT_ERROR_STATUS_CODE;
    logFailure(context, statusCode);

    final HttpServerResponse response = context.response();
    if (response.ended() || response.closed() || response.headWritten()) {
      // nothing can be written; Vert.x resets a response whose head was already sent
      return;
    }

    final String message =
        context.failure() instanceof ErrorResponseException errorResponse
            ? errorResponse.getMessage()
            : HttpResponseStatus.valueOf(statusCode).reasonPhrase();
    response
        .setStatusCode(statusCode)
        .putHeader(CONTENT_TYPE, JSON_UTF_8)
        .end(new JsonObject().put("code", statusCode).put("message", message).encode());
  }

  private static void logFailure(final RoutingContext context, final int statusCode) {
    if (statusCode >= 500) {
      LOG.error("Failed request: {} ({})", getRequestUri(context), statusCode, context.failure());
    } else if (LOG.isDebugEnabled()) {
      LOG.debug("Failed request: {} ({})", getRequestUri(context), statusCode, context.failure());
    }
  }

  private static String getRequestUri(final RoutingContext context) {
    try {
      return Optional.ofNullable(context.request().absoluteURI()).orElse("[null uri]");
    } catch (final RuntimeException e) {
      // absoluteURI() can throw when the Host header is missing or malformed.
      LOG.warn("Vertx failed to calculate request URI due to missing or malformed host header.");
      return "[null uri]";
    }
  }
}
