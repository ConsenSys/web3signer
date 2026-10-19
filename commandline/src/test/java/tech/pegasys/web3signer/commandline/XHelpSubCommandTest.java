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
package tech.pegasys.web3signer.commandline;

import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.web3signer.commandline.subcommands.Eth1SubCommand;
import tech.pegasys.web3signer.commandline.subcommands.Eth2SubCommand;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

public class XHelpSubCommandTest {

  private static final String STABILITY_NOTICE =
      "No stability or compatibility guarantee applies to unstable options. "
          + "They may be added, changed or removed between releases without announcement.";
  private static final String ETH2_HEADING = "Unstable options for web3signer eth2:";
  private static final String ETH1_HEADING = "Unstable options for web3signer eth1:";

  /** All unstable options on the eth2 subtree, in declaration order. */
  private static final List<String> ETH2_OPTIONS =
      List.of(
          "--Xnetwork-altair-fork-epoch",
          "--Xnetwork-bellatrix-fork-epoch",
          "--Xnetwork-capella-fork-epoch",
          "--Xnetwork-deneb-fork-epoch",
          "--Xnetwork-electra-fork-epoch",
          "--Xnetwork-fulu-fork-epoch",
          "--Xnetwork-gloas-fork-epoch",
          "--Xtrusted-setup",
          "--Xsigning-ext-enabled",
          "--Xslashing-protection-db-connection-pool-enabled",
          "--Xazure-endpoint-override",
          "--Xazure-authority-host-override",
          "--Xazure-trust-certificate-override",
          "--Xkey-manager-skip-keystore-storage");

  /** The only unstable options on the eth1 subtree. */
  private static final List<String> ETH1_OPTIONS =
      List.of(
          "--Xazure-endpoint-override",
          "--Xazure-authority-host-override",
          "--Xazure-trust-certificate-override");

  private StringWriter commandOutput;
  private StringWriter commandError;
  private PrintWriter outputWriter;
  private PrintWriter errorWriter;
  private CommandlineParser parser;

  @BeforeEach
  void setup() {
    commandOutput = new StringWriter();
    commandError = new StringWriter();
    outputWriter = new PrintWriter(commandOutput, true);
    errorWriter = new PrintWriter(commandError, true);
    parser =
        new CommandlineParser(
            new Web3SignerBaseCommand(), outputWriter, errorWriter, Collections.emptyMap());
    parser.registerSubCommands(new Eth2SubCommand());
    parser.registerSubCommands(new Eth1SubCommand());
  }

  @AfterEach
  void cleanup() {
    if (outputWriter != null) {
      outputWriter.close();
    }
    if (errorWriter != null) {
      errorWriter.close();
    }
  }

  @ParameterizedTest(name = "{index}: {1}")
  @MethodSource("provideRootVariations")
  void rootXHelpListsEveryUnstableOption(final String[] args, final String description) {
    final int result = parser.parseCommandLine(args);

    assertThat(result).as(description + " - result code").isZero();
    assertThat(commandError.toString()).as(description + " - std err").isEmpty();

    final String output = commandOutput.toString();
    assertThat(output).as(description + " - std out").startsWith(STABILITY_NOTICE);
    assertThat(output).contains(ETH2_HEADING);
    assertThat(output).contains(ETH1_HEADING);
    assertThat(output).contains("Override the Fulu fork activation epoch.");
    assertThat(output).doesNotContain("Starting Web3Signer version");
    assertThat(output).doesNotContain("EXPERIMENTAL").doesNotContain("Experimental");

    ETH2_OPTIONS.forEach(
        option -> assertThat(output).as(description + " - " + option).contains(option));
    ETH1_OPTIONS.forEach(
        option -> assertThat(output).as(description + " - " + option).contains(option));

    assertThat(unstableOptionLines(output)).hasSize(ETH2_OPTIONS.size() + ETH1_OPTIONS.size());
  }

  private static Stream<Arguments> provideRootVariations() {
    return Stream.of(
        Arguments.of(new String[] {"-X"}, "with -X alias"),
        Arguments.of(new String[] {"--Xhelp"}, "with --Xhelp alias"),
        Arguments.of(new String[] {"Xhelp"}, "with Xhelp subcommand"));
  }

  @ParameterizedTest(name = "{index}: {1}")
  @MethodSource("provideEth2Variations")
  void subcommandXHelpListsOnlySubtreeOptions(final String[] args, final String description) {
    final int result = parser.parseCommandLine(args);

    assertThat(result).as(description + " - result code").isZero();

    final String output = commandOutput.toString();
    assertThat(output).as(description + " - std out").contains(ETH2_HEADING);
    assertThat(output).contains("--Xtrusted-setup");
    assertThat(output).doesNotContain(ETH1_HEADING);
    assertThat(unstableOptionLines(output)).hasSize(ETH2_OPTIONS.size());
  }

  private static Stream<Arguments> provideEth2Variations() {
    return Stream.of(
        Arguments.of(new String[] {"eth2", "-X"}, "eth2 with -X alias"),
        Arguments.of(new String[] {"eth2", "--Xhelp"}, "eth2 with --Xhelp alias"),
        Arguments.of(new String[] {"eth2", "Xhelp"}, "eth2 with Xhelp subcommand"));
  }

  @Test
  void eth1XhelpPrintsExactOutput() {
    final int result = parser.parseCommandLine("eth1", "-X");

    assertThat(result).isZero();
    assertThat(commandError.toString()).isEmpty();

    final String expected =
        String.join(
            "\n",
            STABILITY_NOTICE,
            "",
            ETH1_HEADING,
            "      --Xazure-endpoint-override=<URI>",
            "         Override Azure Key Vault endpoint.",
            "      --Xazure-authority-host-override=<URI>",
            "         Override the Microsoft Entra ID (Azure AD) authority host.",
            "      --Xazure-trust-certificate-override=<FILE>",
            "         Trust the given X.509 certificate file for TLS connections to the",
            "           Azure Key Vault endpoint and Microsoft Entra ID authority, in place",
            "           of the platform default trust store.");

    assertThat(commandOutput.toString().stripTrailing()).isEqualTo(expected.stripTrailing());
  }

  @Test
  void unstableOptionsPreserveDeclarationOrder() {
    parser.parseCommandLine("-X");

    final String output = commandOutput.toString();
    assertThat(output.indexOf("--Xnetwork-altair-fork-epoch"))
        .isLessThan(output.indexOf("--Xnetwork-fulu-fork-epoch"));
    assertThat(output.indexOf("--Xnetwork-fulu-fork-epoch"))
        .isLessThan(output.indexOf("--Xtrusted-setup"));
  }

  @Test
  void helpOutputEqualsReferenceBuildAndDoesNotListXhelp() {
    final CommandLine expectedBaseCommandLine = new CommandLine(new Web3SignerBaseCommand());
    expectedBaseCommandLine.setCaseInsensitiveEnumValuesAllowed(true);
    expectedBaseCommandLine.addSubcommand(new Eth2SubCommand());
    expectedBaseCommandLine.addSubcommand(new Eth1SubCommand());
    final String expectedUsage = expectedBaseCommandLine.getUsageMessage();

    final int result = parser.parseCommandLine("--help");

    assertThat(result).isZero();
    assertThat(commandOutput.toString()).isEqualTo(expectedUsage);
    assertThat(commandOutput.toString()).doesNotContain("Xhelp");
  }

  @ParameterizedTest(name = "{index}: {1}")
  @MethodSource("provideHelpPointerVariations")
  void helpPointsAtTheUnstableListing(final String[] args, final String expectedPointer) {
    final int result = parser.parseCommandLine(args);

    assertThat(result).isZero();
    assertThat(commandOutput.toString())
        .as("std out")
        .contains("Unstable options are omitted from this help")
        .contains(expectedPointer);
  }

  private static Stream<Arguments> provideHelpPointerVariations() {
    return Stream.of(
        Arguments.of(new String[] {"--help"}, "'web3signer -X'"),
        Arguments.of(new String[] {"eth2", "--help"}, "'web3signer eth2 -X'"),
        Arguments.of(new String[] {"eth1", "--help"}, "'web3signer eth1 -X'"));
  }

  @Test
  void xhelpIsNotRegisteredOnDescendantsWithoutUnstableOptions() {
    final int result = parser.parseCommandLine("eth2", "export", "-X");

    assertThat(result).isNotZero();
    assertThat(commandError.toString()).contains("Unknown option: '-X'");
  }

  private static List<String> unstableOptionLines(final String output) {
    return output.lines().map(String::trim).filter(line -> line.startsWith("--X")).toList();
  }
}
