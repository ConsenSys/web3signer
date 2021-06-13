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
package tech.pegasys.web3signer.core.service.http;

import tech.pegasys.teku.api.schema.BLSPubKey;
import tech.pegasys.teku.api.schema.BLSSignature;
import tech.pegasys.teku.provider.BLSPubKeyDeserializer;
import tech.pegasys.teku.provider.BLSPubKeySerializer;
import tech.pegasys.teku.provider.BLSSignatureDeserializer;
import tech.pegasys.teku.provider.BLSSignatureSerializer;
import tech.pegasys.teku.provider.Bytes32Deserializer;
import tech.pegasys.teku.provider.Bytes4Deserializer;
import tech.pegasys.teku.provider.Bytes4Serializer;
import tech.pegasys.teku.provider.BytesDeserializer;
import tech.pegasys.teku.provider.BytesSerializer;
import tech.pegasys.teku.provider.DoubleDeserializer;
import tech.pegasys.teku.provider.DoubleSerializer;
import tech.pegasys.teku.provider.SszBitlistDeserializer;
import tech.pegasys.teku.provider.SszBitlistSerializer;
import tech.pegasys.teku.provider.SszBitvectorDeserializer;
import tech.pegasys.teku.provider.SszBitvectorSerializer;
import tech.pegasys.teku.provider.UInt64Deserializer;
import tech.pegasys.teku.provider.UInt64Serializer;
import tech.pegasys.teku.ssz.collections.SszBitlist;
import tech.pegasys.teku.ssz.collections.SszBitvector;
import tech.pegasys.teku.ssz.type.Bytes4;
import tech.pegasys.web3signer.common.JacksonSerializers.HexDeserialiser;
import tech.pegasys.web3signer.common.JacksonSerializers.HexSerialiser;
import tech.pegasys.web3signer.common.JacksonSerializers.StringUInt64Deserializer;
import tech.pegasys.web3signer.common.JacksonSerializers.StringUInt64Serialiser;
import tech.pegasys.web3signer.core.multikey.metadata.parser.SigningMetadataModule.Bytes32Serializer;

import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

public class SigningJsonModule extends SimpleModule {

  public SigningJsonModule() {
    super("SigningJsonRpcModule");
    addDeserializer(Bytes.class, new HexDeserialiser());
    addSerializer(Bytes.class, new HexSerialiser());
    addDeserializer(UInt64.class, new StringUInt64Deserializer());
    addSerializer(UInt64.class, new StringUInt64Serialiser());
    addDeserializer(
        tech.pegasys.teku.infrastructure.unsigned.UInt64.class, new UInt64Deserializer());
    addSerializer(tech.pegasys.teku.infrastructure.unsigned.UInt64.class, new UInt64Serializer());

    addSerializer(Bytes32.class, new Bytes32Serializer());
    addDeserializer(Bytes32.class, new Bytes32Deserializer());
    addDeserializer(Bytes4.class, new Bytes4Deserializer());
    addSerializer(Bytes4.class, new Bytes4Serializer());
    addDeserializer(Bytes.class, new BytesDeserializer());
    addSerializer(Bytes.class, new BytesSerializer());
    addDeserializer(Double.class, new DoubleDeserializer());
    addSerializer(Double.class, new DoubleSerializer());

    addSerializer(BLSPubKey.class, new BLSPubKeySerializer());
    addDeserializer(BLSPubKey.class, new BLSPubKeyDeserializer());
    addDeserializer(BLSSignature.class, new BLSSignatureDeserializer());
    addSerializer(BLSSignature.class, new BLSSignatureSerializer());

    addSerializer(SszBitlist.class, new SszBitlistSerializer());
    addDeserializer(SszBitlist.class, new SszBitlistDeserializer());
    addDeserializer(SszBitvector.class, new SszBitvectorDeserializer());
    addSerializer(SszBitvector.class, new SszBitvectorSerializer());
  }
}
