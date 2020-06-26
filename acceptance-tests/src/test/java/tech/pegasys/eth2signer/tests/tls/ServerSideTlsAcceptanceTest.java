/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.eth2signer.tests.tls;

import static io.restassured.RestAssured.given;
import static java.nio.file.Files.writeString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.fail;
import static org.hamcrest.text.IsEqualIgnoringCase.equalToIgnoringCase;
import static tech.pegasys.eth2signer.dsl.tls.TlsClientHelper.createRequestSpecification;
import static tech.pegasys.eth2signer.dsl.utils.WaitUtils.waitFor;
import static tech.pegasys.eth2signer.tests.tls.support.CertificateHelpers.populateFingerprintFile;

import tech.pegasys.eth2signer.core.config.ClientAuthConstraints;
import tech.pegasys.eth2signer.core.config.TlsOptions;
import tech.pegasys.eth2signer.dsl.signer.Signer;
import tech.pegasys.eth2signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.eth2signer.dsl.tls.BasicTlsOptions;
import tech.pegasys.eth2signer.dsl.tls.ClientTlsConfig;
import tech.pegasys.eth2signer.dsl.tls.TlsCertificateDefinition;
import tech.pegasys.eth2signer.tests.tls.support.BasicClientAuthConstraints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerSideTlsAcceptanceTest {

  // To create a PKCS12 keystore file (containing a privKey and Certificate:
  // Read
  // https://www.digitalocean.com/community/tutorials/openssl-essentials-working-with-ssl-certificates-private-keys-and-csrs
  // To make this simpler, have a config file, containing:
  //  [req]
  //  distinguished_name = req_distinguished_name
  //  x509_extensions = v3_req
  //  prompt = no
  //  [req_distinguished_name]
  //  C = AU
  //  ST = QLD
  //  L = Brisbane
  //  O = PegaSys
  //  OU = Prod Dev
  //  CN = localhost
  //  [v3_req]
  //  keyUsage = keyEncipherment, dataEncipherment
  //  extendedKeyUsage = serverAuth
  //  subjectAltName = @alt_names
  //  [alt_names]
  //  DNS.1 = localhost
  //  IP.1 = 127.0.0.1
  // 1. Create a CSR and private key
  // CMD    = openssl req -newkey rsa:2048 -nodes -keyout domain.key -out domain.csr -config conf
  // OUTPUT = domain.csr, and domain.key in the current working directory
  // 2. Generate self-signed certificate
  // CMD    = openssl req -key domain.key -new -x509 -days 365 -out cert.crt -config conf
  // OUTPUT = cert.crt in the current working directory
  // 3. Convert to PKCS12
  // openssl pkcs12 -export -inkey domain.key -in cert.crt -out cert1.pfx
  //
  @TempDir Path dataPath;

  final TlsCertificateDefinition cert1 =
      TlsCertificateDefinition.loadFromResource("tls/cert1.pfx", "password");
  final TlsCertificateDefinition cert2 =
      TlsCertificateDefinition.loadFromResource("tls/cert2.pfx", "password2");

  private Signer signer = null;

  @AfterEach
  void cleanup() {
    if (signer != null) {
      signer.shutdown();
      signer = null;
    }
  }

  private Signer createTlsEthSigner(
      final TlsCertificateDefinition serverPresentedCerts,
      final TlsCertificateDefinition clientExpectedCert,
      final TlsCertificateDefinition clientCertInServerWhitelist,
      final TlsCertificateDefinition clientToPresent,
      final int fixedListenPort) {

    try {
      final SignerConfigurationBuilder configBuilder =
          new SignerConfigurationBuilder().withHttpPort(fixedListenPort);

      final ClientAuthConstraints clientAuthConstraints;
      if (clientCertInServerWhitelist != null) {
        final Path fingerPrintFilePath = dataPath.resolve("known_clients");
        populateFingerprintFile(fingerPrintFilePath, clientCertInServerWhitelist, Optional.empty());
        clientAuthConstraints = BasicClientAuthConstraints.fromFile(fingerPrintFilePath.toFile());
      } else {
        clientAuthConstraints = null;
      }

      final Path passwordPath = dataPath.resolve("keystore.passwd");
      if (serverPresentedCerts.getPassword() != null) {
        writeString(passwordPath, serverPresentedCerts.getPassword());
      }

      final TlsOptions serverOptions =
          new BasicTlsOptions(
              serverPresentedCerts.getPkcs12File(),
              passwordPath.toFile(),
              Optional.ofNullable(clientAuthConstraints));
      configBuilder.withServerTlsOptions(serverOptions);

      final ClientTlsConfig clientTlsConfig;
      if (clientExpectedCert != null) {
        clientTlsConfig = new ClientTlsConfig(clientExpectedCert, clientToPresent);
      } else {
        clientTlsConfig = null;
      }

      return new Signer(configBuilder.build(), clientTlsConfig);
    } catch (final Exception e) {
      fail("Failed to create EthSigner.", e);
      return null;
    }
  }

  @Test
  void ableToConnectWhenClientExpectsSameCertificateAsThatPresented() {
    signer = createTlsEthSigner(cert1, cert1, null, null, 0);
    signer.start();
    signer.awaitStartupCompletion();

    signer
        .getSpec()
        .when()
        .get("/upcheck")
        .then()
        .assertThat()
        .statusCode(200)
        .body(equalToIgnoringCase("OK"));
  }

  @Test
  void nonTlsClientsCannotConnectToTlsEnabledEthSigner() {
    // The ethSigner object (and in-built requester are already TLS enabled, so need to make a new
    // http client which does not have TLS enabled
    signer = createTlsEthSigner(cert1, cert1, null, null, 0);
    signer.start();
    signer.awaitStartupCompletion();

    Runnable request =
        () ->
            given()
                .baseUri(signer.getUrl())
                .when()
                .get("/upcheck")
                .then()
                .assertThat()
                .statusCode(200)
                .body(equalToIgnoringCase("OK"));

    assertThatThrownBy(request::run).isInstanceOf(SSLHandshakeException.class);
  }

  @Test
  void missingPasswordFileResultsInEthSignerExiting() {
    // arbitrary listen-port to prevent waiting for portfile (during Start) to be created.
    final TlsCertificateDefinition missingPasswordCert =
        TlsCertificateDefinition.loadFromResource("tls/cert1.pfx", null);
    signer = createTlsEthSigner(missingPasswordCert, cert1, null, null, 9000);
    signer.start();
    waitFor(() -> assertThat(signer.isRunning()).isFalse());
  }

  @Test
  void ethSignerExitsIfPasswordDoesntMatchKeyStoreFile() {
    // arbitrary listen-port to prevent waiting for portfile (during Start) to be created.
    final TlsCertificateDefinition wrongPasswordCert =
        TlsCertificateDefinition.loadFromResource("tls/cert1.pfx", "wrongPassword");
    signer = createTlsEthSigner(wrongPasswordCert, cert1, null, null, 9000);
    signer.start();
    waitFor(() -> assertThat(signer.isRunning()).isFalse());
  }

  @Test
  void clientCannotConnectIfExpectedServerCertDoesntMatchServerSuppliedCert() {
    signer = createTlsEthSigner(cert1, cert1, null, null, 0);
    signer.start();
    signer.awaitStartupCompletion();

    final ClientTlsConfig clientTlsConfig = new ClientTlsConfig(cert2, null);

    Runnable request =
        () ->
            given()
                .spec(createRequestSpecification(Optional.of(clientTlsConfig)))
                .baseUri(signer.getUrl())
                .when()
                .get("/upcheck")
                .then()
                .assertThat()
                .statusCode(200)
                .body(equalToIgnoringCase("OK"));

    assertThatThrownBy(request::run).isInstanceOf(SSLHandshakeException.class);
  }

  @Test
  void missingKeyStoreFileResultsInEthSignerExiting() throws IOException {
    final TlsOptions serverOptions =
        new BasicTlsOptions(
            dataPath.resolve("missing_keystore").toFile(),
            Files.writeString(dataPath.resolve("password"), "password").toFile(),
            Optional.empty());

    // Requires arbitrary port to avoid waiting for Ports file
    final SignerConfigurationBuilder configBuilder =
        new SignerConfigurationBuilder().withServerTlsOptions(serverOptions).withHttpPort(9000);

    signer = new Signer(configBuilder.build(), null);
    signer.start();
    waitFor(() -> assertThat(signer.isRunning()).isFalse());
  }

  @Test
  void clientMissingFromWhiteListCannotConnectToEthSigner() {
    signer = createTlsEthSigner(cert1, cert1, cert1, cert1, 0);
    signer.start();
    signer.awaitStartupCompletion();

    final ClientTlsConfig clientTlsConfig = new ClientTlsConfig(cert1, cert2);

    Runnable request =
        () ->
            given()
                .spec(createRequestSpecification(Optional.of(clientTlsConfig)))
                .baseUri(signer.getUrl())
                .when()
                .get("/upcheck")
                .then()
                .assertThat()
                .statusCode(200)
                .body(equalToIgnoringCase("OK"));

    assertThatThrownBy(request::run).isInstanceOf(SSLException.class);
  }
}
