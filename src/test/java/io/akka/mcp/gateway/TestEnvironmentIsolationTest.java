package io.akka.mcp.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class TestEnvironmentIsolationTest {

    private static final Pattern ENV_OVERRIDE = Pattern.compile("\\$\\{\\?([A-Z0-9_]+)}");
    private static final Pattern EXCLUDED_PROPERTY = Pattern.compile(
            "<test\\.excludedEnvironmentVariables>(.*?)</test\\.excludedEnvironmentVariables>", Pattern.DOTALL);

    @Test
    public void everyEnvironmentOverrideInApplicationConf_isExcludedFromTheTestJvm() throws IOException {
        assertThat(excludedInPom()).containsExactlyInAnyOrderElementsOf(overriddenInApplicationConf());
    }

    @Test
    public void environmentOverridesFromApplicationConf_areNotVisibleToTests() throws IOException {
        var visible = overriddenInApplicationConf().stream().filter(name -> System.getenv(name) != null).toList();
        assertThat(visible).isEmpty();
    }

    private static Set<String> overriddenInApplicationConf() throws IOException {
        try (InputStream in = TestEnvironmentIsolationTest.class.getResourceAsStream("/application.conf")) {
            Matcher m = ENV_OVERRIDE.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return m.results().map(r -> r.group(1)).collect(Collectors.toSet());
        }
    }

    private static Set<String> excludedInPom() throws IOException {
        Matcher m = EXCLUDED_PROPERTY.matcher(Files.readString(Path.of("pom.xml")));
        assertThat(m.find()).as("test.excludedEnvironmentVariables property in pom.xml").isTrue();
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
