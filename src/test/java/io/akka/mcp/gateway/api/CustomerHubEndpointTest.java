package io.akka.mcp.gateway.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomerHubEndpointTest {

    @Test
    public void connectRequest_toString_neverShowsTheToken() {
        assertThat(new CustomerHubEndpoint.ConnectRequest("hubtoken-super-secret").toString()).doesNotContain("super-secret");
    }

    @Test
    public void originOf_equivalentSpellings_areEqual() {
        assertThat(CustomerHubEndpoint.originOf("https://Gateway.Example.com:443/path")).contains("https://gateway.example.com");
        assertThat(CustomerHubEndpoint.originOf("https://gateway.example.com")).contains("https://gateway.example.com");
        assertThat(CustomerHubEndpoint.originOf("HTTP://localhost:80")).contains("http://localhost");
        assertThat(CustomerHubEndpoint.originOf("http://localhost:9000")).contains("http://localhost:9000");
    }

    @Test
    public void originOf_somethingThatIsNotAnAbsoluteUrl_isEmpty() {
        assertThat(CustomerHubEndpoint.originOf("not an origin")).isEmpty();
        assertThat(CustomerHubEndpoint.originOf("/relative")).isEmpty();
        assertThat(CustomerHubEndpoint.originOf("null")).isEmpty();
    }
}
