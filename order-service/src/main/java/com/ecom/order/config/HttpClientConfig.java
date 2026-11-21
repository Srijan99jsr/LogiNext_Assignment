package com.ecom.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Short timeouts so a slow dependency cannot tie up every request thread during a traffic spike. */
@Configuration
public class HttpClientConfig {

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(3000);
        return f;
    }

    @Bean
    public RestClient productClient(@Value("${app.product-service-url}") String url) {
        return RestClient.builder().baseUrl(url).requestFactory(timeouts()).build();
    }

    @Bean
    public RestClient cartClient(@Value("${app.cart-service-url}") String url) {
        return RestClient.builder().baseUrl(url).requestFactory(timeouts()).build();
    }
}
