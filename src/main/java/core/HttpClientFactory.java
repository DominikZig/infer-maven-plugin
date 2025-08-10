package core;

import java.net.http.HttpClient;
import java.time.Duration;

public class HttpClientFactory {

    private static HttpClient httpClient;

    private HttpClientFactory() {}

    public static HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        }
        return httpClient;
    }
}
