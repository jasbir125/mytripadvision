package com.mytripadvision.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.mytripadvision.exception.ExternalServiceException;
import org.junit.jupiter.api.Test;

class OutageTest {

    @Test
    void namesTheService_andTellsDownFromOutOfQuota() {
        assertThat(Outage.reason(new ExternalServiceException("llm", "HTTP 429"))).isEqualTo("AI model usage limit reached");
        assertThat(Outage.reason(new ExternalServiceException("llm", "ThrottlingException"))).isEqualTo("AI model usage limit reached");
        assertThat(Outage.reason(new ExternalServiceException("aviationstack", "usage_limit_reached")))
                .isEqualTo("Flight data (AviationStack) usage limit reached");
        assertThat(Outage.reason(new ExternalServiceException("tavily", "HTTP 500"))).isEqualTo("Hotel search (Tavily) unavailable");
        assertThat(Outage.reason(new ExternalServiceException("openweather", "timeout or connection error")))
                .isEqualTo("Weather service (OpenWeather) unavailable");
    }

    @Test
    void unknownServicesAndOtherErrors_getAPlainLine() {
        assertThat(Outage.reason(new ExternalServiceException("other", "HTTP 503"))).isEqualTo("other unavailable");
        assertThat(Outage.reason(new IllegalStateException("boom"))).isEqualTo("Unexpected error");
    }
}
