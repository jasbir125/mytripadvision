package com.mytripadvision.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mytripadvision.client.ChatApiLlmClient;
import com.mytripadvision.client.FlightClient;
import com.mytripadvision.client.LlmClient;
import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.client.WeatherClient;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.workflow.Usage;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** The real HTTP clients (timeouts, status handling, mapping, retry) against a local stub server. */
class ExternalClientsTest {

    private static WireMockServer wire;
    // the application sets snake_case in application.yml; the tests do the same
    private final JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    @BeforeAll
    static void start() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
    }

    @AfterAll
    static void stop() {
        wire.stop();
    }

    @BeforeEach
    void reset() {
        wire.resetAll();
    }

    private AppProperties.Http http(String path, String key, Duration read) {
        return new AppProperties.Http(key, "http://localhost:" + wire.port() + path, Duration.ofSeconds(2), read, 5);
    }

    private AppProperties props(String key, Duration read) {
        return new AppProperties(
                new AppProperties.Llm("openai", "gpt-4o-mini", "gpt-4o", null, key, "", "http://localhost:" + wire.port() + "/llm",
                        "http://unused", Duration.ofSeconds(2), read),
                http("/aviation", key, read), http("/tavily", key, read), http("/weather", key, read), null, null);
    }

    private FlightClient flights(String key, Duration read) {
        return new FlightClient(props(key, read), json);
    }

    private WeatherClient weather() {
        return new WeatherClient(props("k", Duration.ofSeconds(2)), json);
    }

    private LlmClient llm(String key) {
        return new ChatApiLlmClient(props(key, Duration.ofSeconds(2)).llm());
    }

    @Test
    void flights_areMappedToTheDocumentedFields() {
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"data": [{"flight_status": "active",
                          "departure": {"airport": "Delhi", "scheduled": "2026-11-10T10:00:00+00:00", "terminal": "3", "gate": "12", "delay": 15},
                          "arrival": {"airport": "Bangkok", "scheduled": "2026-11-10T16:00:00+00:00", "delay": null},
                          "airline": {"name": "Thai Airways"}, "flight": {"number": "331", "iata": "TG331"}}]}""")));

        String text = flights("k", Duration.ofSeconds(2)).flightsForAirline("Thai Airways", 30);

        var flight = json.readTree(text).path(0);
        assertThat(flight.path("flight_number").asString()).isEqualTo("TG331");
        assertThat(flight.path("airline").asString()).isEqualTo("Thai Airways");
        assertThat(flight.path("status").asString()).isEqualTo("active");
        assertThat(flight.path("departure_airport").asString()).isEqualTo("Delhi");
        assertThat(flight.path("departure_terminal").asString()).isEqualTo("3");
        assertThat(flight.path("departure_gate").asString()).isEqualTo("12");
        assertThat(flight.path("departure_delay").asInt()).isEqualTo(15);
        assertThat(flight.path("arrival_airport").asString()).isEqualTo("Bangkok");
        assertThat(flight.path("arrival_delay").asInt()).isZero();
    }

    @Test
    void anErrorBody_isAFailure_evenWithStatus200() {
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"code\": \"usage_limit_reached\", \"message\": \"limit\"}}")));

        assertThatThrownBy(() -> flights("k", Duration.ofSeconds(2)).flightsForAirline("X", 30))
                .isInstanceOf(ExternalServiceException.class).hasMessage("aviationstack: usage_limit_reached");
    }

    @Test
    void aMonthlyLimit_with429_reportsTheReason_andIsNotRetried() {
        // This is what AviationStack really sends when the free plan's monthly quota is used up.
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"code\": \"usage_limit_reached\", \"message\": \"Your monthly usage limit has been reached.\"}}")));

        assertThatThrownBy(() -> flights("k", Duration.ofSeconds(2)).flightsForAirline("X", 30))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("aviationstack: usage_limit_reached");
        wire.verify(1, getRequestedFor(urlPathEqualTo("/aviation/flights")));
    }

    @Test
    void aRestrictedEndpoint_with403_isAFailure_andIsNotRetried() {
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"type\": \"function_access_restricted\"}}")));

        assertThatThrownBy(() -> flights("k", Duration.ofSeconds(2)).flightsForAirline("X", 30))
                .hasMessage("aviationstack: function_access_restricted");
        wire.verify(1, getRequestedFor(urlPathEqualTo("/aviation/flights")));
    }

    @Test
    void aSlowServer_timesOut_triesTwice_andTheFailureNeverContainsTheKeyOrUrl() {
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).willReturn(aResponse().withFixedDelay(1500).withBody("{}")));

        assertThatThrownBy(() -> flights("SECRET-KEY", Duration.ofMillis(200)).flightsForAirline("X", 30))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("aviationstack: timeout or connection error")
                .hasNoCause();
        wire.verify(2, getRequestedFor(urlPathEqualTo("/aviation/flights")));
    }

    @Test
    void aMissingKey_failsBeforeAnyCall() {
        assertThatThrownBy(() -> flights("", Duration.ofSeconds(2)).flightsForAirline("X", 30))
                .hasMessage("aviationstack: API key is not configured");
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void aServerError_isRetriedOnce_thenSucceeds() {
        wire.stubFor(post(urlPathEqualTo("/llm/chat/completions")).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(502))
                .willSetStateTo("second"));
        wire.stubFor(post(urlPathEqualTo("/llm/chat/completions")).inScenario("retry")
                .whenScenarioStateIs("second").willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": {\"content\": \"hello again\"}}]}")));

        assertThat(llm("k").complete(new Usage(), null, "hi")).isEqualTo("hello again");
        wire.verify(2, postRequestedFor(urlPathEqualTo("/llm/chat/completions")));
    }

    @Test
    void weather_isMapped_andTheForecastKeepsTheFirstFiveEntries() {
        wire.stubFor(get(urlPathEqualTo("/weather/weather")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"name": "Paris", "main": {"temp": 12.5, "feels_like": 11.0, "humidity": 70},
                         "weather": [{"description": "mist"}], "wind": {"speed": 4.0}}""")));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            list.append(i > 0 ? "," : "").append("{\"dt_txt\":\"d").append(i).append("\",\"main\":{\"temp\":").append(i)
                    .append("},\"weather\":[{\"description\":\"rain\"}]}");
        }
        wire.stubFor(get(urlPathEqualTo("/weather/forecast")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"list\":[" + list + "]}")));

        var current = json.readTree(weather().current("Paris"));
        var forecast = json.readTree(weather().forecast("Paris"));

        assertThat(current.path("city").asString()).isEqualTo("Paris");
        assertThat(current.path("temperature_c").asDouble()).isEqualTo(12.5);
        assertThat(current.path("condition").asString()).isEqualTo("mist");
        assertThat(current.path("wind_speed").asDouble()).isEqualTo(4.0);
        assertThat(forecast.path("city").asString()).isEqualTo("Paris");
        assertThat(forecast.path("forecast")).hasSize(5);
    }

    @Test
    void anUnknownCity_isReturnedAsTheErrorBody_likeThePythonTool() {
        wire.stubFor(get(urlPathEqualTo("/weather/weather")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody("{\"cod\":\"404\",\"message\":\"city not found\"}")));

        assertThat(weather().current("Atlantis")).contains("city not found");
    }

    @Test
    void aWeatherServerError_isAFailure() {
        wire.stubFor(get(urlPathEqualTo("/weather/weather")).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> weather().current("Paris"))
                .isInstanceOf(ExternalServiceException.class).hasMessage("openweather: HTTP 502");
    }

    @Test
    void tavilyResults_preferRawContent_andSkipEmptyOnes() {
        wire.stubFor(post(urlPathEqualTo("/tavily/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"results": [{"title": "A", "url": "u1", "content": "short", "raw_content": "full text"},
                                     {"title": "B", "url": "u2", "content": "only content"},
                                     {"title": "C", "url": "u3", "content": ""}]}""")));

        assertThat(new TavilyClient(props("k", Duration.ofSeconds(2))).search("hotels"))
                .extracting(TavilyClient.Document::content).containsExactly("full text", "only content");
    }

    @Test
    void llmReply_isParsed_andTokensAreAddedToTheUsage() {
        wire.stubFor(post(urlPathEqualTo("/llm/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"choices": [{"message": {"content": "hello"}}], "usage": {"prompt_tokens": 1000000, "completion_tokens": 0}}""")));
        Usage usage = new Usage();

        String reply = llm("k").complete(usage, "system", "user");

        assertThat(reply).isEqualTo("hello");
        assertThat(usage.total()).isEqualTo(1_000_000);
        assertThat(usage.cost()).isEqualTo(0.15);   // gpt-4o-mini input price
        assertThat(usage.asMap()).containsEntry("prompt", 1_000_000L).containsEntry("cost_usd", 0.15);
    }

    @Test
    void normalCalls_useTheNormalModel_andTheFinalAnswerUsesTheFinalModel() {
        wire.stubFor(post(urlPathEqualTo("/llm/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"choices": [{"message": {"content": "ok"}}], "usage": {"prompt_tokens": 1000000, "completion_tokens": 0}}""")));
        Usage usage = new Usage();

        llm("k").complete(usage, null, "normal call");
        llm("k").completeWithFinalModel(usage, null, "final answer call");

        wire.verify(1, postRequestedFor(urlPathEqualTo("/llm/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini"))));
        wire.verify(1, postRequestedFor(urlPathEqualTo("/llm/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o"))));
        assertThat(usage.cost()).isEqualTo(2.65);   // 1M input tokens: 0.15 (gpt-4o-mini) + 2.50 (gpt-4o)
    }

    @Test
    void theFinalModel_defaultsPerProvider_whenItIsNotSet() {
        var notSet = new AppProperties.Llm("openai", "gpt-4o-mini", "", null, "k", "", "http://x", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(notSet.resolvedFinalModel()).isEqualTo("gpt-4o");   // openai: a stronger model for the final answer
        assertThat(new AppProperties.Llm("groq", null, null, null, "", "k", "http://x", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1)).resolvedFinalModel()).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void llm_withoutAKey_failsWithoutCallingTheApi() {
        assertThatThrownBy(() -> llm("").complete(new Usage(), null, "hi")).isInstanceOf(ExternalServiceException.class)
                .hasMessage("llm: API key is not configured");
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void llm_unknownProvider_isRejectedAtStartup() {
        var wrong = new AppProperties.Llm("azure", null, null, null, "k", "", "http://x", "http://x", Duration.ofSeconds(1),
                Duration.ofSeconds(1));
        var props = new AppProperties(wrong, null, null, null, null, null);

        assertThatThrownBy(() -> new LlmConfig().llmClient(props, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown LLM_PROVIDER 'azure'");
    }
}
