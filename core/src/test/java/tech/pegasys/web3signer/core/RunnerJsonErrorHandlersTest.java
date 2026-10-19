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
package tech.pegasys.web3signer.core;

import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.web3signer.core.service.http.handlers.ErrorResponseException;

import java.util.concurrent.TimeUnit;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the behaviour {@link Runner#registerJsonErrorHandlers} adds to a router: every 4xx/5xx
 * failure gets a JSON error body, except 405/415, which keep Vert.x's {@code Allow} / {@code
 * Accept} handling.
 */
class RunnerJsonErrorHandlersTest {

  private static final long TIMEOUT_SECONDS = 10;

  private Vertx vertx;
  private HttpServer server;
  private HttpClient client;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    final Router router = Router.router(vertx);
    Runner.registerJsonErrorHandlers(router);
    router.get("/fail/:code").handler(ctx -> ctx.fail(Integer.parseInt(ctx.pathParam("code"))));
    router
        .get("/custom")
        .handler(ctx -> ctx.fail(404, new ErrorResponseException("Unknown pubkey")));
    router.post("/consumes").consumes("application/json").handler(ctx -> ctx.response().end());
    server =
        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    client = vertx.createHttpClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 429, 500, 503})
  void failuresRenderJsonErrorBody(final int code) throws Exception {
    final Reply reply = send(HttpMethod.GET, "/fail/" + code, null, null);

    assertThat(reply.status()).isEqualTo(code);
    assertThat(reply.headers().get("content-type")).isEqualTo("application/json; charset=utf-8");
    assertThat(reply.body())
        .isEqualTo(
            new JsonObject()
                .put("code", code)
                .put("message", HttpResponseStatus.valueOf(code).reasonPhrase())
                .encode());
  }

  @Test
  void errorResponseExceptionMessageIsSent() throws Exception {
    final Reply reply = send(HttpMethod.GET, "/custom", null, null);

    assertThat(reply.status()).isEqualTo(404);
    assertThat(reply.body()).isEqualTo("{\"code\":404,\"message\":\"Unknown pubkey\"}");
  }

  @Test
  void methodNotAllowedKeepsVertxAllowHeader() throws Exception {
    final Reply reply = send(HttpMethod.PUT, "/fail/400", null, null);

    assertThat(reply.status()).isEqualTo(405);
    assertThat(reply.headers().get("allow")).contains("GET");
    assertThat(reply.body()).isEmpty();
    assertThat(reply.headers().get("content-type")).isNull();
  }

  @Test
  void unsupportedMediaTypeKeepsVertxAcceptHeader() throws Exception {
    final Reply reply = send(HttpMethod.POST, "/consumes", "text/plain", "x");

    assertThat(reply.status()).isEqualTo(415);
    assertThat(reply.headers().get("accept")).contains("application/json");
    assertThat(reply.body()).isEmpty();
  }

  private Reply send(
      final HttpMethod method, final String uri, final String contentType, final String body)
      throws Exception {
    return client
        .request(method, server.actualPort(), "127.0.0.1", uri)
        .compose(
            request -> {
              if (contentType != null) {
                request.putHeader("Content-Type", contentType);
              }
              final Future<HttpClientResponse> response =
                  body != null ? request.send(body) : request.send();
              return response.compose(
                  resp ->
                      resp.body()
                          .map(
                              buffer ->
                                  new Reply(resp.statusCode(), resp.headers(), buffer.toString())));
            })
        .toCompletionStage()
        .toCompletableFuture()
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private record Reply(int status, MultiMap headers, String body) {}
}
