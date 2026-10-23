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
package tech.pegasys.web3signer.commandline;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.pegasys.web3signer.CmdlineHelpers.removeFieldFrom;
import static tech.pegasys.web3signer.CmdlineHelpers.validBaseCommandOptions;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_ACCESS_KEY_ID_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_AUTH_MODE_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_ENABLED_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_PREFIXES_FILTER_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_REGION_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_SECRET_ACCESS_KEY_OPTION;
import static tech.pegasys.web3signer.commandline.PicoCliAwsSecretsManagerParameters.AWS_SECRETS_TAG_OPTION;

import tech.pegasys.web3signer.commandline.logging.LoggingFormat;
import tech.pegasys.web3signer.commandline.subcommands.Eth2SubCommand;
import tech.pegasys.web3signer.common.config.AwsAuthenticationMode;
import tech.pegasys.web3signer.core.Context;
import tech.pegasys.web3signer.core.Runner;
import tech.pegasys.web3signer.core.config.BaseConfig;
import tech.pegasys.web3signer.signing.ArtifactSignerProvider;
import tech.pegasys.web3signer.signing.config.DefaultArtifactSignerProvider;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CommandlineParserTest {

  private static final String DEFAULT_USAGE_TEXT =
      new CommandLine(new Web3SignerBaseCommand()).getUsageMessage();

  private StringWriter commandOutput;
  private StringWriter commandError;
  private PrintWriter outputWriter;
  private PrintWriter errorWriter;
  private Web3SignerBaseCommand config;
  private CommandlineParser parser;

  @BeforeEach
  void setup() {
    commandOutput = new StringWriter();
    commandError = new StringWriter();
    outputWriter = new PrintWriter(commandOutput, true);
    errorWriter = new PrintWriter(commandError, true);
    config = new MockWeb3SignerBaseCommand();
    parser = new CommandlineParser(config, outputWriter, errorWriter, Collections.emptyMap());
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

  @Test
  void fullyPopulatedCommandLineParsesIntoVariables() {
    final int result = parser.parseCommandLine(validBaseCommandOptions().split(" "));

    assertThat(result).isZero();

    assertThat(config.getLogLevel()).isEqualTo(Level.INFO);
    assertThat(config.getLoggingFormat()).isEqualTo(LoggingFormat.PLAIN);
    assertThat(config.getHttpListenHost()).isEqualTo("localhost");
    assertThat(config.getHttpListenPort()).isEqualTo(5001);
    assertThat(config.getIdleConnectionTimeoutSeconds()).isEqualTo(45);
  }

  @Test
  void missingLoggingDefaultsToInfoLevel() {
    // Must recreate config before executions, to prevent stale data remaining in the object.
    missingOptionalParameterIsValidAndMeetsDefault("logging", config::getLogLevel, Level.INFO);
  }

  @Test
  void missingLoggingFormatDefaultsToPlain() {
    missingOptionalParameterIsValidAndMeetsDefault(
        "logging-format", config::getLoggingFormat, LoggingFormat.PLAIN);
  }

  @Test
  void loggingFormatChangeIsValid() {
    final int result = parser.parseCommandLine("--logging-format=ECS");
    assertThat(result).isZero();
    assertThat(config.getLoggingFormat()).isEqualTo(LoggingFormat.ECS);
  }

  @Test
  void missingListenHostDefaultsToLoopback() {
    missingOptionalParameterIsValidAndMeetsDefault(
        "http-listen-host",
        config::getHttpListenHost,
        InetAddress.getLoopbackAddress().getHostAddress());
  }

  @Test
  void unknownCommandLineOptionDisplaysErrorMessage() {
    final int result = parser.parseCommandLine("--nonExistentOption=9");
    assertThat(result).isNotZero();
    assertThat(commandOutput.toString()).isEqualTo(DEFAULT_USAGE_TEXT);
  }

  @Test
  void missingIdleConnectionDefaultsToThirtySeconds() {
    missingOptionalParameterIsValidAndMeetsDefault(
        "idle-connection-timeout-seconds", config::getIdleConnectionTimeoutSeconds, 30);
  }

  @Test
  void eth2SubcommandRequiresSlashingDatabaseUrlWhenSlashingEnabled() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 --slashing-protection-enabled=true";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isNotZero();
    assertThat(commandError.toString()).contains("Missing slashing protection database url");
  }

  @Test
  void pushMetricsParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        "--metrics-push-enabled --metrics-push-port 9091 --metrics-push-host=127.0.0.1 --metrics-push-interval=30 --metrics-push-prometheus-job=\"web3signer\" eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
    assertThat(commandError.toString()).isEmpty();
  }

  @Test
  void metricsEnabledWithMetricsPushEnabledFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--metrics-enabled --metrics-push-enabled eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --metrics-enabled option and --metrics-push-enabled option can't be used at the same time.  Please refer to CLI reference for more details about this constraint");
  }

  @Test
  void missingAzureKeyVaultParamsProducesSuitableError() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 --slashing-protection-enabled=false --azure-vault-enabled=true";
    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains("Azure Key Vault was enabled, but the following parameters were missing");
  }

  @Test
  void eth2SubcommandSlashingDatabasePruningEpochsMustBePositive() {
    String cmdline = validBaseCommandOptions();
    cmdline =
        cmdline
            + "eth2 --slashing-protection-db-url=jdbc:mock --slashing-protection-pruning-epochs-to-keep=0";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains("Error parsing parameters: Pruning epochsToKeep must be 1 or more. Value was 0.");
  }

  @Test
  void eth2SubcommandSlashingDatabasePruningIntervalMustBePositive() {
    String cmdline = validBaseCommandOptions();
    cmdline =
        cmdline
            + "eth2 --slashing-protection-db-url=jdbc:mock --slashing-protection-pruning-interval=0";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains("Error parsing parameters: Pruning interval must be 1 or more. Value was 0.");
  }

  @Test
  void eth2SubcommandSlashingDatabasePruningSlotsPerEpochMustBePositive() {
    String cmdline = validBaseCommandOptions();
    cmdline =
        cmdline
            + "eth2 --slashing-protection-db-url=jdbc:mock --slashing-protection-pruning-slots-per-epoch=0";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: Pruning slots per epoch must be 1 or more. Value was 0.");
  }

  @Test
  void eth2SubcommandSlashingDatabaseUrlNotRequiredWhenSlashingDisabled() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isZero();
  }

  @Test
  void missingToInExportShowsError() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 export";
    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString()).contains("--to has not been specified");
  }

  @Test
  void missingFromInImportShowsError() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 import";
    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString()).contains("--from has not been specified");
  }

  @Test
  void missingDbUrlFromImportShowsError() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 import --from ./test.json";
    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains("--slashing-protection-db-url has not been specified");
  }

  @Test
  void missingDbUrlInExportShowsError() {
    String cmdline = validBaseCommandOptions();
    cmdline = cmdline + "eth2 export --to=./out.json";
    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains("--slashing-protection-db-url has not been specified");
  }

  @Test
  void tlsKeystoreWithoutPasswordFileFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --tls-keystore-file must be specified together with --tls-keystore-password-file");
  }

  @Test
  void tlsKeystorePasswordWithoutKeystoreFileFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-password-file=./keystorefile.pass ";
    cmdline += "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --tls-keystore-file must be specified together with --tls-keystore-password-file");
  }

  @Test
  void tlsOptionsWithoutRequiredClientAuthOptionsFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --tls-known-clients-file must be specified if both --tls-allow-any-client and --tls-allow-ca-clients are set to false");
  }

  @Test
  void tlsOptionsWithAllowAnyClientParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "--tls-allow-any-client=true ";
    cmdline += "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isZero();
  }

  @Test
  void tlsOptionsWithAllowCAClientsParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "--tls-allow-ca-clients=true ";
    cmdline += "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isZero();
  }

  @Test
  void tlsOptionsWithKnownClientsFileParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "--tls-known-clients-file=./knownClients.txt ";
    cmdline += "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isZero();
  }

  @Test
  void tlsOptionsWithCAAndKnownClientsFileParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "--tls-allow-ca-clients=true ";
    cmdline += "--tls-known-clients-file=./knownClients.txt ";
    cmdline = cmdline + "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));
    assertThat(result).isZero();
  }

  @Test
  void tlsOptionsWithAllowAnyClientAndKnownClientsFileFailsToParses() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "--tls-allow-any-client=true ";
    cmdline += "--tls-known-clients-file=./knownClients.txt ";
    cmdline = cmdline + "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --tls-allow-any-client cannot be set to true when --tls-known-clients-file is specified or --tls-allow-ca-clients is set to true");
  }

  @Test
  void tlsOptionsWithAllowAnyClientAndAllowCAFailsToParses() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--tls-keystore-file=./keystorefile.p12 ";
    cmdline += "--tls-keystore-password-file=./passwordfile.txt ";
    cmdline += "--tls-allow-any-client=true ";
    cmdline += "--tls-allow-ca-clients=true ";
    cmdline = cmdline + "eth2 --slashing-protection-enabled=false";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --tls-allow-any-client cannot be set to true when --tls-known-clients-file is specified or --tls-allow-ca-clients is set to true");
  }

  @Test
  void keystoreOptionsWithBothPasswordDirAndPasswordFileFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline += "eth2 --slashing-protection-enabled=false ";
    cmdline += "--keystores-path=keystores ";
    cmdline += "--keystores-passwords-path=keystore-passwords ";
    cmdline += "--keystores-password-file=password.txt";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: Only one of --keystores-passwords-path or --keystores-password-file options can be specified");
  }

  @Test
  void gcpSpecifiedProjectIdFailsToParseWithoutRequiredParameters() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        String.format(
            "eth2 --slashing-protection-enabled=false %s=true",
            PicoCliGcpSecretManagerParameters.GCP_SECRETS_ENABLED_OPTION);

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --gcp-secrets-enabled=true, but the following parameters were missing [--gcp-project-id].");
  }

  @Test
  void awsSpecifiedAuthModeFailsToParseWithoutRequiredParameters() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        String.format(
            "eth2 --slashing-protection-enabled=false %s=true %s=%s",
            AWS_SECRETS_ENABLED_OPTION,
            AWS_SECRETS_AUTH_MODE_OPTION,
            AwsAuthenticationMode.SPECIFIED);

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: --aws-secrets-auth-mode=SPECIFIED, but the following parameters were missing [--aws-secrets-access-key-id, --aws-secrets-secret-access-key, --aws-secrets-region].");
  }

  @Test
  void awsSecretsUnknownAuthModeFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        String.format(
            "eth2 --slashing-protection-enabled=false %s=%s %s=UNKNOWN",
            AWS_SECRETS_ENABLED_OPTION, true, AWS_SECRETS_AUTH_MODE_OPTION);

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: Invalid value for option '--aws-secrets-auth-mode': expected one of [ENVIRONMENT, SPECIFIED] (case-insensitive) but was 'UNKNOWN'");
  }

  @Test
  void awsSpecifiedAuthModeParseWithAllRequiredParameters() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        String.format(
            "eth2 --slashing-protection-enabled=false %s=%s %s=%s %s=test %s=test %s=us-east-2",
            AWS_SECRETS_ENABLED_OPTION,
            true,
            AWS_SECRETS_AUTH_MODE_OPTION,
            AwsAuthenticationMode.SPECIFIED,
            AWS_SECRETS_ACCESS_KEY_ID_OPTION,
            AWS_SECRETS_SECRET_ACCESS_KEY_OPTION,
            AWS_SECRETS_REGION_OPTION);

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
  }

  @Test
  void awsSpecifiedAuthModeWithFiltersParseSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        String.format(
            "eth2 --slashing-protection-enabled=false %s=%s %s=%s %s=test %s=test %s=us-east-2 %s=p1,p2,p3 %9$s=t1=v1 %9$s t2=v2 %9$s=t3=v3",
            AWS_SECRETS_ENABLED_OPTION,
            true,
            AWS_SECRETS_AUTH_MODE_OPTION,
            AwsAuthenticationMode.SPECIFIED,
            AWS_SECRETS_ACCESS_KEY_ID_OPTION,
            AWS_SECRETS_SECRET_ACCESS_KEY_OPTION,
            AWS_SECRETS_REGION_OPTION,
            AWS_SECRETS_PREFIXES_FILTER_OPTION,
            AWS_SECRETS_TAG_OPTION);

    MockEth2SubCommand mockEth2SubCommand = new MockEth2SubCommand();
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters()).isNull();

    parser.registerSubCommands(mockEth2SubCommand);
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters().getPrefixesFilter())
        .contains("p1", "p2", "p3");
    var expectedTags = Map.of("t1", "v1", "t2", "v2", "t3", "v3");
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters().getTags())
        .containsExactlyInAnyOrderEntriesOf(expectedTags);
  }

  @Test
  void awsWithoutModeDefaultsToSpecified() {
    String cmdline = validBaseCommandOptions();
    cmdline += "eth2 --slashing-protection-enabled=false ";
    cmdline += AWS_SECRETS_ENABLED_OPTION + "=true ";
    cmdline += AWS_SECRETS_ACCESS_KEY_ID_OPTION + "=test ";
    cmdline += AWS_SECRETS_SECRET_ACCESS_KEY_OPTION + "=test ";
    cmdline += AWS_SECRETS_REGION_OPTION + "=us-east-2 ";
    cmdline += AWS_SECRETS_PREFIXES_FILTER_OPTION + "=p1,p2,p3 ";
    cmdline += AWS_SECRETS_TAG_OPTION + " t1=v1 ";
    cmdline += AWS_SECRETS_TAG_OPTION + "=t2=v2|t3=v3";

    MockEth2SubCommand mockEth2SubCommand = new MockEth2SubCommand();
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters()).isNull();

    parser.registerSubCommands(mockEth2SubCommand);
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters().getAuthenticationMode())
        .isEqualTo(AwsAuthenticationMode.SPECIFIED);
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters().getPrefixesFilter())
        .contains("p1", "p2", "p3");
    var expectedTags = Map.of("t1", "v1", "t2", "v2", "t3", "v3");
    assertThat(mockEth2SubCommand.getAwsSecretsManagerParameters().getTags())
        .containsExactlyInAnyOrderEntriesOf(expectedTags);
  }

  @Test
  void vertxWorkerPoolSizeDefaultParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline += "eth2 --slashing-protection-enabled=false";

    MockEth2SubCommand mockEth2SubCommand = new MockEth2SubCommand();
    parser.registerSubCommands(mockEth2SubCommand);
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
    assertThat(mockEth2SubCommand.getConfig().getVertxWorkerPoolSize()).isEqualTo(20);
  }

  @Test
  void vertxWorkerPoolSizeParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline += "--vertx-worker-pool-size=40 eth2 --slashing-protection-enabled=false";

    MockEth2SubCommand mockEth2SubCommand = new MockEth2SubCommand();
    parser.registerSubCommands(mockEth2SubCommand);
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
    assertThat(mockEth2SubCommand.getConfig().getVertxWorkerPoolSize()).isEqualTo(40);
  }

  @Test
  void commitBoostApiEnabledWithoutKeystorePathFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline += "eth2 --slashing-protection-enabled=false --commit-boost-api-enabled=true";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: Commit boost API is enabled, but --proxy-keystores-path not set");
  }

  @Test
  void commitBoostApiEnabledWithoutKeystorePasswordFileFailsToParse() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        "eth2 --slashing-protection-enabled=false --commit-boost-api-enabled=true --proxy-keystores-path=./keystore";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isNotZero();
    assertThat(commandError.toString())
        .contains(
            "Error parsing parameters: Commit boost API is enabled, but --proxy-keystores-password-file not set");
  }

  @Test
  void commitBoostApiEnabledWithKeystorePathAndKeystorePasswordFileParsesSuccessfully() {
    String cmdline = validBaseCommandOptions();
    cmdline +=
        "eth2 --slashing-protection-enabled=false --commit-boost-api-enabled=true --proxy-keystores-path=./keystore --proxy-keystores-password-file=./password";

    parser.registerSubCommands(new MockEth2SubCommand());
    final int result = parser.parseCommandLine(cmdline.split(" "));

    assertThat(result).isZero();
  }

  private <T> void missingOptionalParameterIsValidAndMeetsDefault(
      final String paramToRemove, final Supplier<T> actualValueGetter, final T expectedValue) {

    String cmdLine = removeFieldFrom(validBaseCommandOptions(), paramToRemove);

    final int result = parser.parseCommandLine(cmdLine.split(" "));
    assertThat(result).isZero();
    assertThat(actualValueGetter.get()).isEqualTo(expectedValue);

    // default init logging at INFO level
    final String output = commandOutput.toString().trim();
    assertThat(output)
        // Check timestamp format
        .containsPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}")
        // Check log level
        .contains("INFO")
        // Check logger name
        .contains("Web3SignerInit")
        // Check message
        .contains("Starting Web3Signer version");

    // Ensure it's a single line i.e. not polluted with unnecessary logging statements
    assertThat(output.lines().count()).isEqualTo(1);
  }

  public static class MockEth2SubCommand extends Eth2SubCommand {
    @Override
    public Runner createRunner() {
      return new NoOpRunner(config);
    }

    public BaseConfig getConfig() {
      return config;
    }
  }

  public static class NoOpRunner extends Runner {

    protected NoOpRunner(final BaseConfig baseConfig) {
      super(baseConfig);
    }

    @Override
    public void run() {}

    @Override
    protected List<ArtifactSignerProvider> createArtifactSignerProvider(
        final MetricsSystem metricsSystem) {
      return List.of(
          new DefaultArtifactSignerProvider(List::of, Optional.empty(), Optional.empty()));
    }

    @Override
    protected void populateRouter(final Context context) {}
  }
}
