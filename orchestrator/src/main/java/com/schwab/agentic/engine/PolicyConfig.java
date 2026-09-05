package com.schwab.agentic.engine;

import com.schwab.agentic.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code workflows/policy.json} into a per-rule-name map of raw config values, so
 * {@link RealPolicyEngine}'s rules read their own thresholds and patterns from data on
 * disk rather than from constants compiled into this class. A reviewer who wants to see
 * what the policy actually enforces reads this file, not the Java source.
 */
public final class PolicyConfig {

    private final Map<String, Map<String, Object>> rulesByName;

    private PolicyConfig(Map<String, Map<String, Object>> rulesByName) {
        this.rulesByName = rulesByName;
    }

    @SuppressWarnings("unchecked")
    public static PolicyConfig loadFromFile(Path path) {
        String json;
        try {
            json = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read policy config: " + path, e);
        }
        return loadFromJson(json);
    }

    @SuppressWarnings("unchecked")
    public static PolicyConfig loadFromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        Map<String, Map<String, Object>> rulesByName = new LinkedHashMap<>();
        for (Object ruleObj : (List<Object>) root.get("rules")) {
            Map<String, Object> rule = (Map<String, Object>) ruleObj;
            String name = (String) rule.get("name");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A rule in policy.json is missing its name: " + rule);
            }
            rulesByName.put(name, rule);
        }
        return new PolicyConfig(rulesByName);
    }

    /** Whether a named rule exists and is enabled. A rule absent from the file is treated as disabled. */
    public boolean isEnabled(String ruleName) {
        Map<String, Object> rule = rulesByName.get(ruleName);
        return rule != null && Boolean.TRUE.equals(rule.get("enabled"));
    }

    public int getInt(String ruleName, String key) {
        Object value = requireRule(ruleName).get(key);
        if (!(value instanceof Double d)) {
            throw new IllegalArgumentException(
                "policy.json rule \"" + ruleName + "\" is missing numeric field \"" + key + "\"");
        }
        return d.intValue();
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String ruleName, String key) {
        Object value = requireRule(ruleName).get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                "policy.json rule \"" + ruleName + "\" is missing list field \"" + key + "\"");
        }
        return ((List<Object>) list).stream().map(String::valueOf).toList();
    }

    private Map<String, Object> requireRule(String ruleName) {
        Map<String, Object> rule = rulesByName.get(ruleName);
        if (rule == null) {
            throw new IllegalArgumentException("policy.json has no rule named \"" + ruleName + "\"");
        }
        return rule;
    }
}
