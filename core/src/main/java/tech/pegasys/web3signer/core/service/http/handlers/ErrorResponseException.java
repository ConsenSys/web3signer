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

/**
 * Failure cause whose message {@link JsonErrorHandler} sends to the client as the JSON error {@code
 * message} instead of the HTTP reason phrase. Always pass it with an explicit status code, e.g.
 * {@code context.fail(404, new ErrorResponseException("Unknown pubkey"))}, because {@code
 * RoutingContext.fail(Throwable)} maps any non {@code HttpException} to 500.
 */
public final class ErrorResponseException extends RuntimeException {

  public ErrorResponseException(final String responseMessage) {
    this(responseMessage, null);
  }

  public ErrorResponseException(final String responseMessage, final Throwable cause) {
    // no own stack trace: the log shows this message plus the cause's stack trace
    super(responseMessage, cause, false, false);
  }
}
