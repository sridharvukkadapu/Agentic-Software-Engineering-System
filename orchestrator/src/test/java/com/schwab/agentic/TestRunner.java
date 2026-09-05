package com.schwab.agentic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the orchestrator's test suite without any external test framework.
 *
 * The orchestrator has zero external dependencies by design (CLAUDE.md), so JUnit is not
 * available. Without something to discover test methods, run them, and report results,
 * every test would need its own hand-written {@code main} method with no shared
 * reporting, and a failing test could silently fail to run at all rather than showing up
 * as a failure in a summary count.
 *
 * Test classes are named explicitly on the command line rather than discovered by
 * scanning the classpath, since classpath scanning without a dependency like Reflections
 * or a build-tool plugin would mean either shipping a hand-rolled classpath walker or
 * silently missing test classes, neither of which is worth it for a project of this size.
 * {@code scripts/test.sh} passes every test class it finds under {@code src/test}.
 *
 * A test method is any public, no-argument, void-returning instance method whose name
 * starts with {@code test}. Methods are sorted by name before running: reflection does
 * not guarantee declaration order, and an unordered run is not reproducible between two
 * invocations on the same machine, let alone between machines.
 */
public final class TestRunner {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: TestRunner <fully.qualified.TestClass> [more test classes...]");
            System.exit(2);
        }

        List<TestResult> results = new ArrayList<>();
        for (String className : args) {
            Class<?> testClass = Class.forName(className);
            results.addAll(runTestClass(testClass));
        }

        int passed = 0;
        for (TestResult result : results) {
            if (result.passed) {
                passed++;
                System.out.println("PASS  " + result.testName);
            } else {
                System.out.println("FAIL  " + result.testName);
                System.out.println("      " + result.failureMessage);
            }
        }

        System.out.println();
        System.out.println("PASSED " + passed + "/" + results.size());

        if (passed < results.size()) {
            System.exit(1);
        }
    }

    private static List<TestResult> runTestClass(Class<?> testClass) throws Exception {
        List<Method> testMethods = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (isTestMethod(method)) {
                testMethods.add(method);
            }
        }
        testMethods.sort(Comparator.comparing(Method::getName));

        List<TestResult> results = new ArrayList<>();
        for (Method method : testMethods) {
            String testName = testClass.getSimpleName() + "." + method.getName();
            Object instance = testClass.getDeclaredConstructor().newInstance();
            try {
                method.invoke(instance);
                results.add(TestResult.pass(testName));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                results.add(TestResult.fail(testName, describeFailure(cause)));
            } catch (Exception e) {
                results.add(TestResult.fail(testName, describeFailure(e)));
            }
        }
        return results;
    }

    private static boolean isTestMethod(Method method) {
        return method.getName().startsWith("test")
            && Modifier.isPublic(method.getModifiers())
            && !Modifier.isStatic(method.getModifiers())
            && method.getParameterCount() == 0
            && method.getReturnType() == void.class;
    }

    private static String describeFailure(Throwable cause) {
        StringBuilder message = new StringBuilder();
        message.append(cause.getClass().getSimpleName());
        if (cause.getMessage() != null) {
            message.append(": ").append(cause.getMessage());
        }
        return message.toString();
    }

    private record TestResult(String testName, boolean passed, String failureMessage) {
        static TestResult pass(String testName) {
            return new TestResult(testName, true, null);
        }

        static TestResult fail(String testName, String failureMessage) {
            return new TestResult(testName, false, failureMessage);
        }
    }
}
