/*
 * Copyright 2021 ConsenSys AG.
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
package tech.pegasys.web3signer.tests.keymanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static tech.pegasys.web3signer.tests.keymanager.SlashingProtectionDataChoice.WITHOUT_SLASHING_PROTECTION_DATA;
import static tech.pegasys.web3signer.tests.keymanager.SlashingProtectionDataChoice.WITH_SLASHING_PROTECTION_DATA;

import tech.pegasys.web3signer.core.service.http.ArtifactType;
import tech.pegasys.web3signer.core.service.http.handlers.keymanager.delete.DeleteKeystoresRequestBody;
import tech.pegasys.web3signer.core.service.http.handlers.signing.eth2.Eth2SigningRequestBody;
import tech.pegasys.web3signer.dsl.signer.Signer;
import tech.pegasys.web3signer.dsl.signer.SignerConfiguration;
import tech.pegasys.web3signer.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.web3signer.dsl.utils.Eth2RequestUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DeleteKeystoresAcceptanceTest extends KeyManagerTestBase {
  private static final JsonMapper mapper = JsonMapper.builder().build();
  private static final String BLS_PRIVATE_KEY_1 =
      "3ee2224386c82ffea477e2adf28a2929f5c349165a4196158c7f3a2ecca40f35";

  private static final String SINGLE_ENTRY_SLASHING_DATA =
      """
    {
      "metadata" : {
        "interchange_format_version" : "5",
        "genesis_validators_root" : "0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95"
      },
      "data" : [ {
        "pubkey" : "0x98d083489b3b06b8740da2dfec5cc3c01b2086363fe023a9d7dc1f907633b1ff11f7b99b19e0533e969862270061d884",
        "signed_blocks" : [ {
          "slot" : "19999",
          "signing_root" : "0x4ff6f743a43f3b4f95350831aeaf0a122a1a392922c45d804280284a69eb850b"
        } ],
        "signed_attestations" : [ {
          "source_epoch" : "6",
          "target_epoch" : "7",
          "signing_root" : "0x30752da173420e64a66f6ca6b97c55a96390a3158a755ecd277812488bb84e57"
        } ]
      } ]
    }""";

  private static final String EMPTY_SLASHING_DATA =
      """
    {
      "metadata" : {
        "interchange_format_version" : "5",
        "genesis_validators_root" : "0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95"
      },
      "data" : [ ]
    }""";

  private static final String EMPTY_SLASHING_DATA_WITHOUT_GVR =
      """
    {
      "metadata" : {
        "interchange_format_version" : "5"
      },
      "data" : [ ]
    }""";

  @Test
  public void invalidRequestBodyReturnsError() throws URISyntaxException {
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);
    final Response response = callDeleteKeystores("{\"invalid\": \"json body\"}");
    response
        .then()
        .assertThat()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", is("Bad Request"));
  }

  @Test
  public void internalServerErrorReturnsJsonErrorBody() throws URISyntaxException {
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);
    callDeleteKeystores("{\"pubkeys\": null}")
        .then()
        .assertThat()
        .statusCode(500)
        .contentType(ContentType.JSON)
        .body("message", is("Internal Server Error"));
  }

  @Test
  public void deletingExistingKeyWithNoSlashingProtectionDataTwiceReturnsNotFound()
      throws Exception {
    final String pubKey =
        "0xa46bf94016af71e55ca0518fe6a8bd3852e01b3f959780a4faf3bbe461ac553c0a83f232cc5f2a4b827d8d3455b706e4";
    createBlsKey("eth2/bls_keystore_2.json", "otherpassword");
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);
    String slashingJson =
        callDeleteKeystores(composeRequestBody(pubKey))
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("deleted"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(slashingJson)).isEqualTo(mapper.readTree(EMPTY_SLASHING_DATA));

    // call API again with same key should return not_found
    slashingJson =
        callDeleteKeystores(composeRequestBody(pubKey))
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("not_found"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(slashingJson)).isEqualTo(mapper.readTree(EMPTY_SLASHING_DATA));
  }

  @Test
  public void deletingExistingKeyReturnDeleted() throws Exception {
    createBlsKey("eth2/bls_keystore.json", "somepassword");
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);
    String json =
        callDeleteKeystores(composeRequestBody())
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("deleted"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(SINGLE_ENTRY_SLASHING_DATA));
  }

  @Test
  public void deletingExistingTwiceReturnsNotActive() throws Exception {
    createBlsKey("eth2/bls_keystore.json", "somepassword");
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);
    String json =
        callDeleteKeystores(composeRequestBody())
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("deleted"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(SINGLE_ENTRY_SLASHING_DATA));

    // call API again with same key should return not_active with the same exported slashing
    // protection data
    json =
        callDeleteKeystores(composeRequestBody())
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("not_active"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(SINGLE_ENTRY_SLASHING_DATA));
  }

  @Test
  public void deletingRemovesSignerFromActiveSigners() throws Exception {
    final String firstPubkey = createBlsKey("eth2/bls_keystore.json", "somepassword");
    final String secondPubKey = createBlsKey("eth2/bls_keystore_2.json", "otherpassword");
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);

    callListKeys()
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("data.size()", is(2))
        .and()
        .body("data[0].validating_pubkey", is(firstPubkey))
        .and()
        .body("data[1].validating_pubkey", is(secondPubKey));

    String json =
        callDeleteKeystores(composeRequestBody())
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("deleted"))
            .extract()
            .path("slashing_protection");
    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(SINGLE_ENTRY_SLASHING_DATA));

    callListKeys()
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("data.size()", is(1))
        .and()
        .body("data[0].validating_pubkey", is(secondPubKey));
  }

  @Test
  public void deletingReadOnlyKeyReturnError() throws Exception {
    final String readOnlyPubkey = createRawPrivateKeyFile(BLS_PRIVATE_KEY_1);
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);
    String json =
        callDeleteKeystores(composeRequestBody(readOnlyPubkey))
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("error"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(EMPTY_SLASHING_DATA));
  }

  @Test
  public void deletingDisablesSigningForAllWeb3Signers(@TempDir Path signer2KeyStoreDirectory)
      throws Exception {
    final String firstPubkey =
        createBlsKey(testDirectory, "eth2/bls_keystore.json", "somepassword");
    final String secondPubKey =
        createBlsKey(testDirectory, "eth2/bls_keystore_2.json", "otherpassword");
    setupSignerWithKeyManagerApi(WITH_SLASHING_PROTECTION_DATA);

    createBlsKey(signer2KeyStoreDirectory, "eth2/bls_keystore.json", "somepassword");
    createBlsKey(signer2KeyStoreDirectory, "eth2/bls_keystore_2.json", "otherpassword");
    final SignerConfiguration signer2Configuration =
        new SignerConfigurationBuilder()
            .withKeyStoreDirectory(signer2KeyStoreDirectory)
            .withMode("eth2")
            .withNetwork("minimal")
            .withAltairForkEpoch(MINIMAL_ALTAIR_FORK)
            .withSlashingEnabled(true)
            .withSlashingProtectionDbUrl(signer.getSlashingDbUrl())
            .withSlashingProtectionDbUsername(DB_USERNAME)
            .withSlashingProtectionDbPassword(DB_PASSWORD)
            .withKeyManagerApiEnabled(true)
            .build();
    final Signer signer2 = new Signer(signer2Configuration, null);
    signer2.start();
    signer2.awaitStartupCompletion();

    // both signers should have all keys loaded
    callListKeys(signer)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("data.size()", is(2))
        .and()
        .body("data[0].validating_pubkey", is(firstPubkey))
        .body("data[1].validating_pubkey", is(secondPubKey));

    callListKeys(signer2)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("data.size()", is(2))
        .and()
        .body("data[0].validating_pubkey", is(firstPubkey))
        .body("data[1].validating_pubkey", is(secondPubKey));

    String json =
        callDeleteKeystores(composeRequestBody())
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("deleted"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(SINGLE_ENTRY_SLASHING_DATA));

    // after deleting on first signer, it should only have the 2nd key
    callListKeys()
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("data.size()", is(1))
        .and()
        .body("data[0].validating_pubkey", is(secondPubKey));

    // keys on the second signer should be unaffected
    callListKeys(signer2)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("data.size()", is(2))
        .and()
        .body("data[0].validating_pubkey", is(firstPubkey))
        .body("data[1].validating_pubkey", is(secondPubKey));

    final Eth2SigningRequestBody attestationRequest =
        Eth2RequestUtils.createCannedRequest(ArtifactType.ATTESTATION);
    // signing should fail on first signer since the key doesn't exist anymore
    signer.eth2Sign(firstPubkey, attestationRequest, ContentType.TEXT).then().statusCode(404);
    // signing should fail on second signer since it has been disabled in the database
    signer2.eth2Sign(firstPubkey, attestationRequest, ContentType.TEXT).then().statusCode(412);

    final Eth2SigningRequestBody blockRequest =
        Eth2RequestUtils.createCannedRequest(ArtifactType.BLOCK_V2);
    // signing should fail on first signer since the key doesn't exist anymore
    signer.eth2Sign(firstPubkey, blockRequest, ContentType.TEXT).then().statusCode(404);
    // signing should fail on second signer since it has been disabled in the database
    signer2.eth2Sign(firstPubkey, blockRequest, ContentType.TEXT).then().statusCode(412);
  }

  @Test
  public void testRequestBodyParsing() throws IOException {
    final ObjectMapper objectMapper = new ObjectMapper();
    final DeleteKeystoresRequestBody parsedBody =
        objectMapper.readValue(composeRequestBody(), DeleteKeystoresRequestBody.class);
    assertThat(parsedBody.getPubkeys().getFirst())
        .isEqualTo(
            "0x98d083489b3b06b8740da2dfec5cc3c01b2086363fe023a9d7dc1f907633b1ff11f7b99b19e0533e969862270061d884");
  }

  @Test
  public void deletingExistingKeyWithNoSlashingProtectionReturnDeleted() throws Exception {

    createBlsKey("eth2/bls_keystore.json", "somepassword");

    setupSignerWithKeyManagerApi(WITHOUT_SLASHING_PROTECTION_DATA);

    String json =
        callDeleteKeystores(composeRequestBody())
            .then()
            .contentType(ContentType.JSON)
            .assertThat()
            .statusCode(200)
            .body("data[0].status", is("deleted"))
            .extract()
            .path("slashing_protection");

    assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(EMPTY_SLASHING_DATA_WITHOUT_GVR));
  }

  private String composeRequestBody() {
    return composeRequestBody(
        "0x98d083489b3b06b8740da2dfec5cc3c01b2086363fe023a9d7dc1f907633b1ff11f7b99b19e0533e969862270061d884");
  }

  private String composeRequestBody(final String pubkey) {
    final JsonObject requestBody = new JsonObject().put("pubkeys", new JsonArray().add(pubkey));
    return requestBody.toString();
  }
}
