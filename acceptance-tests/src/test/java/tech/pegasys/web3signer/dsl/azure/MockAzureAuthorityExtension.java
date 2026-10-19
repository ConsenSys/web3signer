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
package tech.pegasys.web3signer.dsl.azure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;

import tech.pegasys.web3signer.dsl.tls.TlsCertificateDefinition;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension providing an in-JVM mock Microsoft Entra ID (Azure AD) authority, backed by
 * WireMock, that answers any client-credential token request with {@link
 * AzureKeyVaultEmulator#EMULATOR_JWT} - the same well-formed dummy token the Azure Key Vault
 * emulator itself documents as its default authentication token. This lets production {@code
 * ClientSecretCredentialBuilder} code acquire a token the normal way (a real HTTPS POST to a
 * configured authority) without ever needing live Azure AD.
 *
 * <p>Register as a {@code static} field with {@code @RegisterExtension} so every test method in the
 * class shares the same host/port: started once before the class's first test, stopped once after
 * its last, regardless of individual test outcomes.
 *
 * <p>Reuses {@link AzureKeyVaultEmulator}'s own self-signed certificate, so the same trust
 * certificate override that trusts the emulator's vault endpoint also trusts this server.
 */
public final class MockAzureAuthorityExtension implements BeforeAllCallback, AfterAllCallback {

  private WireMockExtension wireMock;

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    final TlsCertificateDefinition certDefinition =
        AzureKeyVaultEmulator.getInstance().getTlsCertificateDefinition();

    wireMock =
        WireMockExtension.newInstance()
            .options(
                WireMockConfiguration.wireMockConfig()
                    .httpDisabled(true)
                    .dynamicHttpsPort()
                    .keystorePath(certDefinition.getPkcs12File().getAbsolutePath())
                    .keystorePassword(certDefinition.getPassword())
                    .keyManagerPassword(certDefinition.getPassword())
                    .keystoreType("PKCS12"))
            .build();
    wireMock.beforeAll(context);

    wireMock.stubFor(
        any(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"token_type\":\"Bearer\",\"expires_in\":315360000,"
                            + "\"ext_expires_in\":315360000,\"access_token\":\""
                            + AzureKeyVaultEmulator.EMULATOR_JWT
                            + "\"}")));
  }

  @Override
  public void afterAll(final ExtensionContext context) throws Exception {
    wireMock.afterAll(context);
  }

  public void rejectCredentials(final String clientId, final String clientSecret) {
    wireMock.stubFor(
        post(anyUrl())
            .atPriority(1)
            .withRequestBody(containing("client_id=" + clientId))
            .withRequestBody(containing("client_secret=" + clientSecret))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":\"invalid_client\","
                            + "\"error_description\":\"Invalid client credentials\"}")));
  }

  public String getAuthorityHostUrl() {
    return "https://127.0.0.1:" + wireMock.getHttpsPort();
  }
}
