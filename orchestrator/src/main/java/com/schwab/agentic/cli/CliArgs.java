package com.schwab.agentic.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal command-line argument parser: {@code --flag value} pairs, bare boolean flags
 * (like {@code --live}), and positional arguments, with no external dependency. Every
 * required value that is missing throws naming exactly what was expected, since a CLI
 * that silently defaults a required argument to null would fail confusingly much later,
 * far from where the actual problem is.
 */
final class CliArgs {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<String> flags = new ArrayList<>();
    private final List<String> positionals = new ArrayList<>();

    private CliArgs() {
    }

    static CliArgs parse(String[] args) {
        CliArgs parsed = new CliArgs();
        // args[0] is the subcommand name itself, already dispatched on; parsing starts after it.
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                if (hasValue) {
                    parsed.values.put(arg, args[i + 1]);
                    i++;
                } else {
                    parsed.flags.add(arg);
                }
            } else {
                parsed.positionals.add(arg);
            }
        }
        return parsed;
    }

    boolean hasFlag(String name) {
        return flags.contains(name) || values.containsKey(name);
    }

    String requireValue(String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value;
    }

    String valueOrDefault(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    Path requirePath(String name) {
        return Path.of(requireValue(name));
    }

    Path pathOrDefault(String name, Path defaultValue) {
        String value = values.get(name);
        return value == null ? defaultValue : Path.of(value);
    }

    String requirePositional() {
        if (positionals.isEmpty()) {
            throw new IllegalArgumentException("Missing required positional argument (e.g. a node id)");
        }
        return positionals.get(0);
    }
}
