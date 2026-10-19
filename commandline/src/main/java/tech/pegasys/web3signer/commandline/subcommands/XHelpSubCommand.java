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
package tech.pegasys.web3signer.commandline.subcommands;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Prints help text for unstable options.
 *
 * <p>Unstable options are distinguished by
 *
 * <ul>
 *   <li>Being marked as 'hidden'
 *   <li>Having their first option name start with <code>--X</code>
 * </ul>
 *
 * <p>No stability or compatibility guarantee applies to unstable options between releases. They can
 * be added and removed without announcement, and their meaning and values can similarly change
 * without announcement or warning.
 */
@CommandLine.Command(
    name = XHelpSubCommand.COMMAND_NAME,
    aliases = {"-X", "--Xhelp"},
    description = "This command provides help text for all unstable options.",
    hidden = true,
    helpCommand = true)
public class XHelpSubCommand implements Runnable, CommandLine.IHelpCommandInitializable2 {

  public static final String COMMAND_NAME = "Xhelp";
  private static final String UNSTABLE_OPTION_PREFIX = "--X";
  private static final String STABILITY_NOTICE =
      "No stability or compatibility guarantee applies to unstable options. "
          + "They may be added, changed or removed between releases without announcement.";

  private CommandLine self;
  private Help.ColorScheme colorScheme;
  private PrintWriter out;

  @Override
  public void init(
      final CommandLine helpCommandLine,
      final Help.ColorScheme colorScheme,
      final PrintWriter outWriter,
      final PrintWriter errWriter) {
    this.self = helpCommandLine;
    this.colorScheme = colorScheme;
    this.out = outWriter;
  }

  @Override
  public void run() {
    out.println(STABILITY_NOTICE);
    out.println();
    printUnstableOptions(self.getCommandSpec().parent());
  }

  private void printUnstableOptions(final CommandSpec commandSpec) {
    final List<OptionSpec> unstableOptions = new ArrayList<>();
    for (final OptionSpec option : commandSpec.options()) {
      if (option.names()[0].startsWith(UNSTABLE_OPTION_PREFIX)) {
        unstableOptions.add(option);
      }
    }

    if (!unstableOptions.isEmpty()) {
      // Recreate the options with hidden flipped to false so that they are rendered.
      final CommandSpec revealedSpec = CommandSpec.create();
      revealedSpec.usageMessage().sortOptions(false);
      for (final OptionSpec option : unstableOptions) {
        revealedSpec.addOption(option.toBuilder().hidden(false).build());
      }
      out.printf("Unstable options for %s:%n", commandSpec.qualifiedName());
      out.println(new Help(revealedSpec, colorScheme).optionList());
    }

    // A subcommand is registered under its name and each of its aliases, so de-duplicate.
    for (final CommandLine subcommand : new LinkedHashSet<>(commandSpec.subcommands().values())) {
      printUnstableOptions(subcommand.getCommandSpec());
    }
  }
}
