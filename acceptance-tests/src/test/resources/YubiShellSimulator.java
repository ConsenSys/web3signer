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
package tech.pegasys.web3signer.tests.signing;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.Console;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

// a dumb YubiHSM simulator for AT which prints BLS or SECP private key on standard output
public class YubiShellSimulator {
  private static final Map<String, String> argsMap = new HashMap<>();

  public static void main(String[] args) {
    for (String arg : args) {
      final String[] kv = arg.split("=");
      if (kv == null || kv.length != 2) {
        System.err.println("Argument should be in format --arg=value");
        System.exit(-1);
      }
      argsMap.put(kv[0], kv[1]);
    }

    // make sure required arguments are passed
    if (!argsMap.containsKey("--connector")) {
      System.err.println("--connector is missing");
      System.exit(-1);
    }
    if (!argsMap.containsKey("--authkey")) {
      System.err.println("--authkey is missing");
      System.exit(-1);
    }
    if (!argsMap.containsKey("--object-id")) {
      System.err.println("--object-id is missing");
      System.exit(-1);
    }
    if (!argsMap.containsKey("--action")) {
      System.err.println("--action is missing");
      System.exit(-1);
    }

    if (!"get-opaque".equals(argsMap.get("--action"))) {
      System.err.println("--action=get-opaque is missing");
      System.exit(-1);
    }

    System.out.println("Session keepalive set up to run every 15 seconds");
    askPassword();
    System.out.println("Created session 0");

    // if object-id = 1, return BLS key otherwise return SECP key
    final String objId = argsMap.get("--object-id");
    if (objId.equals("1")) {
      // BLS
      System.out.println("3ee2224386c82ffea477e2adf28a2929f5c349165a4196158c7f3a2ecca40f35");
    } else {
      // SECP
      System.out.println("8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63");
    }
  }

  private static void askPassword() {
    final Console console = System.console();
    final String password;
    if (console != null) {
      final char[] passwordFromConsole = console.readPassword("Enter Password:");
      password = passwordFromConsole == null ? null : new String(passwordFromConsole);
    } else {
      Scanner scanner = new Scanner(System.in, UTF_8.name());
      password = scanner.nextLine();
    }

    if (password == null) {
      System.err.println("Password is required");
      System.exit(1);
    }
  }
}