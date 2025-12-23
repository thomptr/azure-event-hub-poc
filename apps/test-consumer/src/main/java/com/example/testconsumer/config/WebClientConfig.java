package com.example.testconsumer.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Value("${api.test-api.base-url:http://localhost:8089}")
    private String testApiBaseUrl;

    @Value("${api.test-api.timeout.connect:5000}")
    private int connectTimeout;

    @Value("${api.test-api.timeout.read:10000}")
    private int readTimeout;

    @Value("${api.test-api.timeout.write:10000}")
    private int writeTimeout;

    @Bean
    public WebClient testApiWebClient() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
            .responseTimeout(Duration.ofMillis(readTimeout))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS))
                .addHandlerLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
            .baseUrl(testApiBaseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}

