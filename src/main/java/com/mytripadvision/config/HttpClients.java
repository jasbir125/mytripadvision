package com.mytripadvision.config;

import com.mytripadvision.exception.ExternalServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/** What every external client shares: a RestClient with timeouts, and one retry (also for AWS SDK calls). */
@Slf4j
public final class HttpClients {

    private HttpClients() {
    }

    /** HTTP/1.1 because some providers (and the test stub server) do not accept the h2c upgrade. */
    public static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Runs the call; on an I/O error, 429 or 5xx it waits a moment and tries once more.
     * Whatever fails is turned into an ExternalServiceException that holds no URL, key or remote text.
     */
    public static <T> T callWithRetry(String service, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException first) {
            if (!canRetry(first)) {
                throw failed(service, first);
            }
            log.warn("[{}] call failed ({}), trying once more", service, reason(first));
            sleep(400);
            try {
                return call.get();
            } catch (RuntimeException second) {
                throw failed(service, second);
            }
        }
    }

    private static boolean canRetry(RuntimeException ex) {
        if (ex instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        if (ex instanceof AwsServiceException aws) {
            // ThrottlingException, ServiceUnavailableException, InternalServerException...
            return aws.statusCode() == 429 || aws.statusCode() >= 500 || "ThrottlingException".equals(awsCode(aws));
        }
        // the AWS SDK reports timeouts and connection problems as SdkClientException
        return ex instanceof ResourceAccessException || ex instanceof SdkClientException;
    }

    private static ExternalServiceException failed(String service, RuntimeException ex) {
        if (ex instanceof ExternalServiceException already) {
            return already;
        }
        log.error("[{}] call failed: {}", service, reason(ex));
        return new ExternalServiceException(service, reason(ex));
    }

    private static String reason(RuntimeException ex) {
        if (ex instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        if (ex instanceof AwsServiceException aws) {
            // only the short error code, for example AccessDeniedException: never the message (it has ARNs)
            return awsCode(aws) != null ? awsCode(aws) : "HTTP " + aws.statusCode();
        }
        if (ex instanceof ResourceAccessException || ex instanceof SdkClientException) {
            return "timeout or connection error";
        }
        return ex.getClass().getSimpleName();
    }

    /** The AWS error code if it looks like a plain name, else null. */
    private static String awsCode(AwsServiceException aws) {
        String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
        return code != null && code.matches("[A-Za-z]{1,60}") ? code : null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
