package core;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.inject.Named;
import javax.inject.Singleton;

@Named
@Singleton
public class HttpClientFactory {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
