package io.akka.mcp.gateway.application;

import akka.javasdk.http.HttpClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.RequestBuilder;
import akka.util.ByteString;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Equivalent of langchain4j's {@code StreamableHttpMcpTransportTest}, adapted to
 * {@link AkkaHttpMcpTransport}. The reference test mostly asserts configuration of the
 * transport's own {@code java.net.http.HttpClient} (SSL context, HTTP/2 default, redirect
 * policy) — none of which applies here, since this transport doesn't build its own HTTP
 * client, it obtains one from {@link HttpClientProvider}. What carries over directly is the
 * {@code Mcp-Session-Id} accessor behavior, and in its place we assert the transport actually
 * wires itself up through the provider for the configured URL.
 */
class AkkaHttpMcpTransportTest {

    private static final String URL = "http://localhost/mcp";

    @Test
    void mcpSessionIdShouldBeNullByDefault() {
        AkkaHttpMcpTransport transport = AkkaHttpMcpTransport.builder()
                .httpClientProvider(new RecordingHttpClientProvider(NOOP_HTTP_CLIENT))
                .url(URL)
                .build();

        assertThat(transport.getMcpSessionId()).isNull();
    }

    @Test
    void shouldExposeAndAcceptMcpSessionId() {
        AkkaHttpMcpTransport transport = AkkaHttpMcpTransport.builder()
                .httpClientProvider(new RecordingHttpClientProvider(NOOP_HTTP_CLIENT))
                .url(URL)
                .build();

        transport.setMcpSessionId("session-123");
        assertThat(transport.getMcpSessionId()).isEqualTo("session-123");

        transport.setMcpSessionId(null);
        assertThat(transport.getMcpSessionId()).isNull();
    }

    @Test
    void shouldObtainHttpClientFromProviderForConfiguredUrl() throws Exception {
        var provider = new RecordingHttpClientProvider(NOOP_HTTP_CLIENT);

        AkkaHttpMcpTransport transport = AkkaHttpMcpTransport.builder()
                .httpClientProvider(provider)
                .url(URL)
                .build();

        assertThat(provider.requestedUrl).isEqualTo(URL);
        assertThat(provider.callCount).isEqualTo(1);
        assertThat(extractHttpClient(transport)).isSameAs(NOOP_HTTP_CLIENT);
    }

    @Test
    void shouldRequireHttpClientProvider() {
        assertThatThrownBy(() -> AkkaHttpMcpTransport.builder().url(URL).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireUrl() {
        assertThatThrownBy(() -> AkkaHttpMcpTransport.builder()
                        .httpClientProvider(new RecordingHttpClientProvider(NOOP_HTTP_CLIENT))
                        .build())
                .isInstanceOf(NullPointerException.class);
    }

    // ---- helpers ----

    private static final HttpClient NOOP_HTTP_CLIENT = new HttpClient() {
        @Override public RequestBuilder<ByteString> GET(String uri) { throw new UnsupportedOperationException(); }
        @Override public RequestBuilder<ByteString> POST(String uri) { throw new UnsupportedOperationException(); }
        @Override public RequestBuilder<ByteString> PUT(String uri) { throw new UnsupportedOperationException(); }
        @Override public RequestBuilder<ByteString> PATCH(String uri) { throw new UnsupportedOperationException(); }
        @Override public RequestBuilder<ByteString> DELETE(String uri) { throw new UnsupportedOperationException(); }
    };

    /** Records the url it was asked to build a client for, and hands back a fixed stub client. */
    private static final class RecordingHttpClientProvider implements HttpClientProvider {
        private final HttpClient clientToReturn;
        private String requestedUrl;
        private int callCount;

        RecordingHttpClientProvider(HttpClient clientToReturn) {
            this.clientToReturn = clientToReturn;
        }

        @Override
        public HttpClient httpClientFor(String name) {
            this.requestedUrl = name;
            this.callCount++;
            return clientToReturn;
        }
    }

    private static HttpClient extractHttpClient(AkkaHttpMcpTransport transport) throws Exception {
        Field field = AkkaHttpMcpTransport.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(transport);
    }
}
