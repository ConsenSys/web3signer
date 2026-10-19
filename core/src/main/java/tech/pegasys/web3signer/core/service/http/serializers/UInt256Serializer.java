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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.apache.tuweni.units.bigints.UInt256;

/** UInt256 is represented as a decimal string on the wire (e.g. base_fee_per_gas). */
public class UInt256Serializer extends JsonSerializer<UInt256> {
  public UInt256Serializer() {}

  @Override
  public void serialize(UInt256 value, JsonGenerator gen, SerializerProvider serializers)
      throws IOException {
    gen.writeString(value.toBigInteger().toString());
  }
}
