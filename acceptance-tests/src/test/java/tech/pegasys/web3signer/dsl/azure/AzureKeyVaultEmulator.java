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

import tech.pegasys.web3signer.dsl.tls.TlsCertificateDefinition;
import tech.pegasys.web3signer.keystore.dsl.certificates.CertificateHelpers;
import tech.pegasys.web3signer.keystore.dsl.certificates.SelfSignedCertificate;
import tech.pegasys.web3signer.signing.secp256k1.EthPublicKeyUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.net.ssl.TrustManagerFactory;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.models.ImportKeyOptions;
import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyType;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.web3j.crypto.ECKeyPair;
import org.web3j.utils.Numeric;
import reactor.core.publisher.Mono;

/**
 * JVM-wide singleton Testcontainers-backed Azure Key Vault emulator, seeded with the BLS/SECP test
 * fixtures previously required to be imported by hand into a real Azure Key Vault. Consumed by
 * every Azure-related acceptance test class in place of live Azure. Never explicitly stopped;
 * Testcontainers' Ryuk reaper removes the container at JVM exit.
 *
 * <p>Does not itself serve the mock Microsoft Entra ID (Azure AD) authority; see {@link
 * MockAzureAuthorityExtension} for that, registered separately by each test class.
 */
public final class AzureKeyVaultEmulator {

  private static final Logger LOG = LogManager.getLogger();

  // Automatically set by the gradle acceptanceTest task from gradle.properties'
  // azureKeyVaultEmulatorImage; a single property covering registry/repo/tag makes it a one-line
  // change to migrate to an upstream image if/when our changes are incorporated there.
  private static final DockerImageName IMAGE = dockerImageName();
  private static final int EMULATOR_PORT = 11001;
  private static final String CERT_PASSWORD = "emulator";

  // Fixed (not secret) tenant id shared between the emulator container's AUTH__TENANTID and the
  // client-secret credential configured in tests, so forks with no CI secrets still work.
  public static final String TENANT_ID = "11111111-2222-3333-4444-555555555555";
  // Not a real credential: a syntactically valid but publicly-known dummy JWT, matching the
  // azure-keyvault-emulator's own documented default authentication token. The emulator's
  // JwtBearer handler only validates that a bearer token is well-formed; it never verifies
  // signature, issuer, audience or lifetime, so this is accepted regardless of its source.
  // Package-private: also used by MockAzureAuthorityExtension to answer token requests.
  static final String EMULATOR_JWT = // NOSONAR - not a real/hard-coded secret
      """
      eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.\
      eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNzM1Njg5NjAwLCJleHAiOjQxMDI0NDQ4MDAsImlzcyI6Imh0dHBzOi8vbG9jYWxob3N0LyJ9.\
      42D_zJ3qM02NM_ExWU9S9jvNGMfpop3YuWT9lFqJ5yU""";

  public static final String BLS_SECRET_NAME = "BLS-TEST-KEYS";
  public static final String BLS_TAGGED_SECRET_NAME = "BLS-TEST-TAGGED-KEY";
  public static final String SECP_18_KEY_NAME = "SECP-18";
  public static final String SECP_19_KEY_NAME = "SECP-19";
  public static final String SECP_20_TAGGED_KEY_NAME = "SECP-20-TAGGED";

  // Tags every fixture below, so tests loading "everything" stay scoped even after bulk-load
  // scale tests seed extra, differently-tagged keys into this same shared emulator instance.
  public static final String FIXTURE_TAG_KEY = "FIXTURE_SET";
  public static final String FIXTURE_TAG_VALUE = "core";

  // Values copied verbatim from the (now-removed) "Azure Key Vault BLS/SECP Test Keys" README
  // sections.
  private static final List<String> BLS_TEST_PRIVATE_KEYS =
      List.of(
          "0x60b420bb3851d9d47acb933dbe70399bf6c92da33af01d4fb770e98c0325f41d",
          "0x73d51abbd89cb8196f0efb6892f94d68fccc2c35f0b84609e5f12c55dd85aba8",
          "0x39722cbbf8b91a4b9045c5e6175f1001eac32f7fcd5eccda5c6e62fc4e638508",
          "0x4c9326bb9805fa8f85882c12eae724cef0c62e118427f5948aefa5c428c43c93",
          "0x384a62688ee1d9a01c9d58e303f2b3c9bc1885e8131565386f75f7ae6ca8d147",
          "0x4b6b5c682f2db7e510e0c00ed67ac896c21b847acadd8df29cf63a77470989d2",
          "0x13086d684f4b1a1632178a8c5be08a2fb01287c4a78313c41373701eb8e66232",
          "0x25296867ee96fa5b275af1b72f699efcb61586565d4c3c7e41f4b3e692471abd",
          "0x10e1a313e573d96abe701d8848742cf88166dd2ded38ac22267a05d1d62baf71",
          "0x0bdeebbad8f9b240192635c42f40f2d02ee524c5a3fe8cda53fb4897b08c66fe");
  private static final String BLS_TAGGED_PRIVATE_KEY =
      "0x5e8d5667ce78982a07242739ab03dc63c91e830c80a5b6adca777e3f216a405d";
  private static final String SECP_18_PRIVATE_KEY_HEX =
      "a492823c3e193d6c595f37a18e3c06650cf4c74558cc818b16130b293716106f";
  private static final String SECP_19_PRIVATE_KEY_HEX =
      "c5114526e042343c6d1899cad05e1c00ba588314de9b96929914ee0df18d46b2";
  private static final String SECP_20_PRIVATE_KEY_HEX =
      "04b9f63ecf84210c5366c66d68fa1f5da1fa4f634fad6dfc86178e4d79ff9e59";

  private static final class Holder {
    private static final AzureKeyVaultEmulator INSTANCE = new AzureKeyVaultEmulator();
  }

  public static AzureKeyVaultEmulator getInstance() {
    return Holder.INSTANCE;
  }

  private static DockerImageName dockerImageName() {
    final String image = System.getProperty("azureKeyVaultEmulatorImage");
    if (image == null) {
      throw new IllegalStateException("System property [azureKeyVaultEmulatorImage] is missing.");
    }
    return DockerImageName.parse(image);
  }

  private final GenericContainer<?> container;
  private final TlsCertificateDefinition tlsCertificateDefinition;
  private final Path certificatePath;
  private final KeyClient keyClient;

  private AzureKeyVaultEmulator() {
    try {
      final SelfSignedCertificate selfSignedCertificate =
          SelfSignedCertificate.generate(List.of("localhost.vault.azure.net"));
      final Path certDir = Files.createTempDirectory("azure-keyvault-emulator-cert");
      final Path crtFile = certDir.resolve("emulator.crt");
      selfSignedCertificate.writeCertificateToFile(crtFile);
      this.certificatePath = crtFile;

      final Path pfxFile =
          CertificateHelpers.createPkcs12TrustStore(certDir, selfSignedCertificate, CERT_PASSWORD);
      // The emulator container process runs as a different (non-root) uid and needs read access
      // to the copied file.
      if (!pfxFile.toFile().setReadable(true, false)) {
        throw new IllegalStateException("Unable to make " + pfxFile + " world-readable");
      }
      this.tlsCertificateDefinition = new TlsCertificateDefinition(pfxFile.toFile(), CERT_PASSWORD);

      this.container =
          new GenericContainer<>(IMAGE)
              .withExposedPorts(EMULATOR_PORT)
              .withCopyFileToContainer(
                  MountableFile.forHostPath(pfxFile), "/app/.certs/emulator.pfx")
              .withEnv("ASPNETCORE_Kestrel__Certificates__Default__Password", CERT_PASSWORD)
              .withEnv(
                  "ASPNETCORE_Kestrel__Certificates__Default__Path", "/app/.certs/emulator.pfx")
              .withEnv("AUTH__TENANTID", TENANT_ID)
              .waitingFor(
                  Wait.forHttps("/").forPort(EMULATOR_PORT).forStatusCode(200).allowInsecure());
      LOG.info(
          "Starting Azure Key Vault emulator container ({})...", IMAGE.asCanonicalNameString());
      container.start();
      LOG.info("Azure Key Vault emulator listening at {}", getVaultUrl());

      this.keyClient =
          new KeyClientBuilder()
              .vaultUrl(getVaultUrl())
              .credential(emulatorTokenCredential())
              .disableChallengeResourceVerification()
              .httpClient(buildTrustingHttpClient(crtFile))
              .buildClient();

      seedFixtures(crtFile);
    } catch (final Exception e) {
      throw new IllegalStateException("Unable to start Azure Key Vault emulator", e);
    }
  }

  public String getVaultUrl() {
    // Docker Desktop's published-port userland proxy commonly only forwards the IPv4 loopback
    // address; "localhost" can resolve to the IPv6 loopback first and fail with connection
    // refused even though the port is reachable over IPv4. Pin to 127.0.0.1 in that case.
    final String host = container.getHost();
    final String resolvedHost = "localhost".equals(host) ? "127.0.0.1" : host;
    return "https://" + resolvedHost + ":" + container.getMappedPort(EMULATOR_PORT);
  }

  public TlsCertificateDefinition getTlsCertificateDefinition() {
    return tlsCertificateDefinition;
  }

  /**
   * Path to the emulator's self-signed X.509 certificate, suitable for use as an Azure client trust
   * certificate override (see {@code AzureOverrides}). Also trusted by {@link
   * MockAzureAuthorityExtension}'s mock authority server, which reuses this same certificate (via
   * {@link #getTlsCertificateDefinition()}), so a single trust override covers both endpoints.
   */
  public Path getTrustCertificatePath() {
    return certificatePath;
  }

  private void seedFixtures(final Path certFile) {
    final com.azure.core.http.HttpClient trustingHttpClient = buildTrustingHttpClient(certFile);
    final SecretClient secretClient =
        new SecretClientBuilder()
            .vaultUrl(getVaultUrl())
            .credential(emulatorTokenCredential())
            .disableChallengeResourceVerification()
            .httpClient(trustingHttpClient)
            .buildClient();

    // The emulator only echoes back the "enabled" secret attribute if the request explicitly
    // sets it; when omitted (e.g. via the bare setSecret(name, value) convenience overload) the
    // SDK's own response parser throws a NullPointerException on the now-missing field. Always
    // set it explicitly.
    secretClient.setSecret(
        new KeyVaultSecret(BLS_SECRET_NAME, String.join("\n", BLS_TEST_PRIVATE_KEYS))
            .setProperties(
                new SecretProperties()
                    .setEnabled(true)
                    .setTags(Map.of(FIXTURE_TAG_KEY, FIXTURE_TAG_VALUE))));
    secretClient.setSecret(
        new KeyVaultSecret(BLS_TAGGED_SECRET_NAME, BLS_TAGGED_PRIVATE_KEY)
            .setProperties(
                new SecretProperties()
                    .setEnabled(true)
                    .setTags(Map.of("ENV", "TEST", FIXTURE_TAG_KEY, FIXTURE_TAG_VALUE))));

    final ImportKeyOptions secp18Options =
        new ImportKeyOptions(SECP_18_KEY_NAME, toJsonWebKey(SECP_18_PRIVATE_KEY_HEX));
    secp18Options.setTags(Map.of(FIXTURE_TAG_KEY, FIXTURE_TAG_VALUE));
    keyClient.importKey(secp18Options);
    final ImportKeyOptions secp19Options =
        new ImportKeyOptions(SECP_19_KEY_NAME, toJsonWebKey(SECP_19_PRIVATE_KEY_HEX));
    secp19Options.setTags(Map.of(FIXTURE_TAG_KEY, FIXTURE_TAG_VALUE));
    keyClient.importKey(secp19Options);
    final ImportKeyOptions taggedKeyOptions =
        new ImportKeyOptions(SECP_20_TAGGED_KEY_NAME, toJsonWebKey(SECP_20_PRIVATE_KEY_HEX));
    taggedKeyOptions.setTags(Map.of("ENV", "TEST", FIXTURE_TAG_KEY, FIXTURE_TAG_VALUE));
    keyClient.importKey(taggedKeyOptions);

    LOG.info("Seeded Azure Key Vault emulator with BLS/SECP test fixtures");
  }

  /**
   * Imports {@code count} extra SECP256K1 keys tagged with {@code tags}, for bulk-load scale tests.
   * Use a tag distinct from {@link #FIXTURE_TAG_KEY}.
   */
  public void seedAdditionalSecpKeys(final int count, final Map<String, String> tags) {
    for (int i = 0; i < count; i++) {
      final ImportKeyOptions options =
          new ImportKeyOptions(
              "BULK-SECP-" + i, toJsonWebKey(EthPublicKeyUtils.generateK256KeyPair()));
      options.setTags(tags);
      keyClient.importKey(options);
    }
    LOG.info("Seeded {} additional SECP256K1 keys tagged {}", count, tags);
  }

  private static JsonWebKey toJsonWebKey(final KeyPair keyPair) {
    final ECPrivateKey ecPrivateKey = (ECPrivateKey) keyPair.getPrivate();
    final ECPublicKey ecPublicKey = (ECPublicKey) keyPair.getPublic();
    return new JsonWebKey()
        .setKeyType(KeyType.EC)
        .setCurveName(KeyCurveName.P_256K)
        .setD(Numeric.toBytesPadded(ecPrivateKey.getS(), 32))
        .setX(Numeric.toBytesPadded(ecPublicKey.getW().getAffineX(), 32))
        .setY(Numeric.toBytesPadded(ecPublicKey.getW().getAffineY(), 32));
  }

  private static JsonWebKey toJsonWebKey(final String privateKeyHex) {
    final ECKeyPair ecKeyPair = ECKeyPair.create(Numeric.toBigInt(privateKeyHex));
    return toJsonWebKey(EthPublicKeyUtils.web3JECKeypairToJavaKeyPair(ecKeyPair));
  }

  /**
   * Builds an Azure {@code HttpClient} whose TLS trust store contains exactly the emulator's
   * generated self-signed certificate, used only for seeding fixtures directly from the test JVM.
   * Deliberately avoids mutating global {@code javax.net.ssl.trustStore} system properties, which
   * would affect every other concurrent/subsequent HTTPS call in this JVM.
   */
  private static com.azure.core.http.HttpClient buildTrustingHttpClient(final Path certFile) {
    try {
      final CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
      final X509Certificate certificate;
      try (InputStream in = Files.newInputStream(certFile)) {
        certificate = (X509Certificate) certificateFactory.generateCertificate(in);
      }
      final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      trustStore.setCertificateEntry("azure-keyvault-emulator", certificate);
      final TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);

      final SslContext sslContext =
          SslContextBuilder.forClient().trustManager(trustManagerFactory).build();
      final reactor.netty.http.client.HttpClient reactorHttpClient =
          reactor.netty.http.client.HttpClient.create().secure(spec -> spec.sslContext(sslContext));
      return new NettyAsyncHttpClientBuilder(reactorHttpClient).build();
    } catch (final Exception e) {
      throw new IllegalStateException(
          "Unable to build TLS-trusting HTTP client for Azure Key Vault emulator", e);
    }
  }

  /**
   * Token credential used to seed fixtures directly from this test DSL (not via production signing
   * code), returning the emulator's own well-formed default JWT without any network call.
   */
  private static TokenCredential emulatorTokenCredential() {
    return request ->
        Mono.just(new AccessToken(EMULATOR_JWT, OffsetDateTime.now(ZoneOffset.UTC).plusYears(10)));
  }
}
