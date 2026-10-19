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
package tech.pegasys.web3signer.core.service.http.serializers;

import java.io.IOException;
import java.math.BigInteger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.apache.tuweni.units.bigints.UInt256;

/** UInt256 is represented as a decimal string on the wire (e.g. base_fee_per_gas). */
public class UInt256Deserializer extends JsonDeserializer<UInt256> {
  public UInt256Deserializer() {}

  @Override
  public UInt256 deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    return UInt256.valueOf(new BigInteger(p.getValueAsString()));
  }
}
