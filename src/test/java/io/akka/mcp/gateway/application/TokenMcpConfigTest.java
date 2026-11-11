package io.akka.mcp.gateway.application;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class TokenMcpConfigTest {

    private static final String VALID = """
            {"id":"billing","name":"Billing","tool-prefix":"Billing_","mcp-url":"https://billing.example.com","okta-app-id":"app-1"}""";

    private static String entry(String extra) {
        return VALID.substring(0, VALID.length() - 1) + "," + extra + "}";
    }

    @Test
    public void anEntryWithOnlyTheRequiredSettings_getsTheSafeDefaults() {
        var parsed = TokenMcpConfig.parse("[" + VALID + "]");

        assertThat(parsed).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("billing");
            assertThat(c.name()).isEqualTo("Billing");
            assertThat(c.toolPrefix()).isEqualTo("Billing_");
            assertThat(c.mcpUrl()).isEqualTo("https://billing.example.com");
            assertThat(c.oktaAppId()).isEqualTo("app-1");
            assertThat(c.recordToolContent()).isFalse();
            assertThat(c.connectHint()).isEmpty();
            assertThat(c.connectHintOrDefault()).isEqualTo(TokenMcpConfig.DEFAULT_CONNECT_HINT);
        });
    }

    @Test
    public void theOptionalSettings_areRead() {
        var parsed = TokenMcpConfig.parse("[" + entry("\"record-tool-content\":true,\"connect-hint\":\"Open your profile.\"") + "]");

        assertThat(parsed).singleElement().satisfies(c -> {
            assertThat(c.recordToolContent()).isTrue();
            assertThat(c.connectHint()).contains("Open your profile.");
            assertThat(c.connectHintOrDefault()).isEqualTo("Open your profile.");
        });
    }

    @Test
    public void blankUrlAndAppId_areKept_soTheConnectorStaysOff() {
        var parsed = TokenMcpConfig.parse("[{\"id\":\"billing\",\"name\":\"Billing\",\"tool-prefix\":\"Billing_\",\"mcp-url\":\"\",\"okta-app-id\":\"\"}]");

        assertThat(parsed).singleElement().satisfies(c -> {
            assertThat(c.mcpUrl()).isEmpty();
            assertThat(c.oktaAppId()).isEmpty();
            assertThat(c.isUsable()).isFalse();
        });
    }

    @Test
    public void aBlankSetting_orAnEmptyList_meansNoConnectors() {
        assertThat(TokenMcpConfig.parse("")).isEmpty();
        assertThat(TokenMcpConfig.parse("[]")).isEmpty();
    }

    @Test
    public void textThatIsNotAJsonList_meansNoConnectors() {
        assertThat(TokenMcpConfig.parse("not json")).isEmpty();
        assertThat(TokenMcpConfig.parse("{\"id\":\"billing\"}")).isEmpty();
    }

    @Test
    public void anInvalidEntry_isSkipped_andTheOthersStay() {
        var other = VALID.replace("billing", "shipping").replace("Billing_", "Shipping_").replace("Billing", "Shipping");

        var parsed = TokenMcpConfig.parse("[{\"id\":\"Bad Id\",\"name\":\"X\"}," + other + "]");

        assertThat(parsed).extracting(TokenMcpConfig::id).containsExactly("shipping");
    }

    @Test
    public void everySettingIsChecked() {
        for (var bad : new String[] {
                VALID.replace("\"billing\"", "\"Billing\""),
                VALID.replace("\"billing\"", "\"1billing\""),
                VALID.replace("\"billing\"", "\"\""),
                VALID.replace("\"Billing\",\"tool", "\"\",\"tool"),
                VALID.replace("\"Billing_\"", "\"Billing\""),
                VALID.replace("\"Billing_\"", "\"_\""),
                VALID.replace("\"Billing_\"", "\"howto_\""),
                VALID.replace("\"Billing_\"", "\"HowTo_\""),
                VALID.replace("\"Billing_\"", "\"" + "a".repeat(32) + "_\""),
                VALID.replace("\"Billing\",\"tool", "\"" + "n".repeat(61) + "\",\"tool"),
                VALID.replace("\"Billing\",\"tool", "\"Bil\\u0007ling\",\"tool"),
                VALID.replace("\"Billing\",\"tool", "\"google WORKSPACE Billing\",\"tool"),
                VALID.replace(",\"okta-app-id\":\"app-1\"", ""),
                VALID.replace("\"Billing_\"", "\"Bill ing_\""),
                VALID.replace("\"mcp-url\":\"https://billing.example.com\",", ""),
                VALID.replace("\"okta-app-id\":\"app-1\"", "\"okta-app-id\":5"),
                VALID.replace("\"billing\"", "\"proxy\""),
                VALID.replace("\"billing\"", "\"howto\""),
                VALID.replace("\"billing\"", "\"google-workspace-billing\""),
                VALID.replace("\"billing\"", "\"" + "a".repeat(41) + "\""),
                entry("\"connect-hint\":\"\""),
                entry("\"record-tool-content\":\"yes\""),
                entry("\"connect-hint\":\"" + "x".repeat(301) + "\""),
                entry("\"connect-hint\":\"two\\nlines\"")}) {
            assertThat(TokenMcpConfig.parse("[" + bad + "]")).as(bad).isEmpty();
        }
    }

    @Test
    public void aSettingThatDoesNotExist_refusesTheEntry() {
        assertThat(TokenMcpConfig.parse("[" + entry("\"colour\":\"red\"") + "]")).isEmpty();
        assertThat(TokenMcpConfig.parse("[" + entry("\"record-tool-contents\":true") + "]")).isEmpty();
    }

    @Test
    public void textAfterTheList_orARepeatedKey_meansNoConnectors() {
        assertThat(TokenMcpConfig.parse("[" + VALID + "][" + VALID + "]")).isEmpty();
        assertThat(TokenMcpConfig.parse("[" + VALID.replace("\"id\":\"billing\"", "\"id\":\"billing\",\"id\":\"other\"") + "]")).isEmpty();
    }

    @Test
    public void anEntryThatIsNotAnObject_isSkipped() {
        assertThat(TokenMcpConfig.parse("[\"billing\"," + VALID + "]")).extracting(TokenMcpConfig::id).containsExactly("billing");
    }

    @Test
    public void theLongestAllowedValues_areAccepted() {
        var entry = VALID.replace("\"billing\"", "\"a" + "b".repeat(39) + "\"").replace("\"Billing_\"", "\"B" + "c".repeat(30) + "_\"")
                .replace("\"Billing\",\"tool", "\"" + "n".repeat(60) + "\",\"tool");

        assertThat(TokenMcpConfig.parse("[" + entry + "]")).hasSize(1);
    }

    @Test
    public void trailingSlashesOnTheUrl_areDropped_becauseTheGatewayAddsThePath() {
        var parsed = TokenMcpConfig.parse("[" + VALID.replace("billing.example.com\"", "billing.example.com//\"") + "]");

        assertThat(parsed).singleElement().satisfies(c -> assertThat(c.mcpUrl()).isEqualTo("https://billing.example.com"));
    }

    @Test
    public void aSecondEntryWithTheSameId_orTheSameToolPrefix_isSkipped() {
        var sameId = VALID.replace("Billing_", "Other_").replace("\"Billing\"", "\"Other\"");
        var samePrefix = VALID.replace("\"billing\"", "\"invoices\"").replace("\"Billing\"", "\"Invoices\"");
        var distinct = VALID.replace("\"billing\"", "\"shipping\"").replace("Billing_", "Shipping_").replace("\"Billing\"", "\"Shipping\"");

        var parsed = TokenMcpConfig.parse("[" + VALID + "," + sameId + "," + samePrefix + "," + distinct + "]");

        assertThat(parsed).extracting(TokenMcpConfig::id).containsExactly("billing", "shipping");
    }

    @Test
    public void isUsable_needsAnOktaAppAndAnHttpsOrLoopbackUrl() {
        assertThat(usable("https://billing.example.com", "app")).isTrue();
        assertThat(usable("http://127.0.0.1:9000", "app")).isTrue();
        assertThat(usable("http://localhost:9000", "app")).isTrue();
        assertThat(usable("http://[::1]:9000", "app")).isTrue();

        assertThat(usable("", "app")).isFalse();
        assertThat(usable("https://billing.example.com", "")).isFalse();
        assertThat(usable("http://billing.example.com", "app")).isFalse();
        assertThat(usable("http://localhost.example.com", "app")).isFalse();
        assertThat(usable("http://127.0.0.1.example.com", "app")).isFalse();
        assertThat(usable("https://user:secret@billing.example.com", "app")).isFalse();
        assertThat(usable("https://billing.example.com?key=secret", "app")).isFalse();
        assertThat(usable("https://billing.example.com#frag", "app")).isFalse();
        assertThat(usable("not a url", "app")).isFalse();
        assertThat(usable("https:/billing", "app")).isFalse();
        assertThat(usable("//billing.example.com", "app")).isFalse();
    }

    @Test
    public void aUrlWithoutASchemeSeparator_isLeftAsItIs() {
        var parsed = TokenMcpConfig.parse("[" + VALID.replace("https://billing.example.com", "Billing:8080") + "]");

        assertThat(parsed).singleElement().satisfies(c -> assertThat(c.mcpUrl()).isEqualTo("Billing:8080"));
    }

    @Test
    public void anUppercaseScheme_isLowercased_soTheConnectorWorks() {
        var parsed = TokenMcpConfig.parse("[" + VALID.replace("https://billing.example.com", "HTTPS://Billing.example.com/") + "]");

        assertThat(parsed).singleElement().satisfies(c -> {
            assertThat(c.mcpUrl()).isEqualTo("https://Billing.example.com");
            assertThat(c.isUsable()).isTrue();
        });
    }

    @Test
    public void aHintOfTheLongestAllowedLength_isAccepted() {
        assertThat(TokenMcpConfig.parse("[" + entry("\"connect-hint\":\"" + "h".repeat(300) + "\"") + "]")).hasSize(1);
    }

    @Test
    public void aSecondEntryWithTheSameName_isSkipped() {
        var sameName = VALID.replace("\"billing\"", "\"invoices\"").replace("Billing_", "Invoices_").replace("\"Billing\"", "\"BILLING\"");

        assertThat(TokenMcpConfig.parse("[" + VALID + "," + sameName + "]")).extracting(TokenMcpConfig::id).containsExactly("billing");
    }

    private static boolean usable(String url, String appId) {
        return new TokenMcpConfig("billing", "Billing", "Billing_", url, appId, false, Optional.empty()).isUsable();
    }

    @Test
    public void theSettingIsReadFromTheConfig() {
        var config = ConfigFactory.parseString("token-mcps = \"\"\"[" + VALID + "]\"\"\"");

        assertThat(TokenMcpConfig.from(config)).extracting(TokenMcpConfig::id).containsExactly("billing");
    }

    @Test
    public void theDefaultConfig_setsNoConnector() {
        assertThat(TokenMcpConfig.from(ConfigFactory.load())).isEmpty();
    }

    @Test
    public void toString_namesOnlyTheId() {
        assertThat(TokenMcpConfig.parse("[" + VALID + "]").get(0).toString()).isEqualTo("TokenMcpConfig[id=billing]");
    }
}
