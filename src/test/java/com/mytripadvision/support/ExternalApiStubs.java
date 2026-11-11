package com.mytripadvision.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import tools.jackson.databind.json.JsonMapper;

/** Canned answers of the LLM, AviationStack, Tavily and OpenWeather for the end-to-end tests. */
public final class ExternalApiStubs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExternalApiStubs() {
    }

    /** Replies to chat-completion requests whose body contains {@code marker}; 10 prompt + 5 completion tokens. */
    public static void llm(WireMockServer wire, String marker, String reply, int priority) {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "choices", java.util.List.of(java.util.Map.of("message", java.util.Map.of("content", reply))),
                "usage", java.util.Map.of("prompt_tokens", 10, "completion_tokens", 5)));
        wire.stubFor(post(urlEqualTo("/llm/chat/completions")).atPriority(priority)
                .withRequestBody(containing(marker))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    public static void llmFails(WireMockServer wire, String marker) {
        llmFails(wire, marker, 500);
    }

    public static void llmFails(WireMockServer wire, String marker, int httpStatus) {
        wire.stubFor(post(urlEqualTo("/llm/chat/completions")).atPriority(1)
                .withRequestBody(containing(marker))
                .willReturn(aResponse().withStatus(httpStatus).withBody("{\"error\":\"secret upstream detail\"}")));
    }

    /** AviationStack answers every airline with its monthly-quota error (HTTP 429). */
    public static void flightQuotaExhausted(WireMockServer wire) {
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).atPriority(1)
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"code\": \"usage_limit_reached\", \"type\": \"usage_limit_reached\"}}")));
    }

    public static void hotelSearchDown(WireMockServer wire) {
        wire.stubFor(post(urlEqualTo("/tavily/search")).atPriority(1).willReturn(aResponse().withStatus(500)));
    }

    public static void weatherDown(WireMockServer wire) {
        wire.stubFor(get(urlPathEqualTo("/weather/weather")).atPriority(1).willReturn(aResponse().withStatus(503)));
    }

    public static void defaults(WireMockServer wire) {
        // supervisor decisions (specific markers first)
        llm(wire, "CLARIFY-ME", "{\"agents\": [], \"clarification\": \"Which city?\", \"query\": \"hotels\"}", 1);
        llm(wire, "NO-DESTINATION", "{\"agents\": [\"flight\",\"hotel\",\"weather\",\"budget\",\"itinerary\"], "
                + "\"clarification\": null, \"query\": \"a trip\", "
                + "\"trip_constraints\": {\"destination\": null, \"origin\": null}}", 1);
        llm(wire, "WEATHER-ONLY", "{\"agents\": [\"weather\"], \"query\": \"weather in Tokyo\", "
                + "\"trip_constraints\": {\"destination\": \"Tokyo\"}}", 1);
        llm(wire, "FAIL-BUDGET", "{\"agents\": [\"budget\"], \"query\": \"FAIL-BUDGET trip\", "
                + "\"trip_constraints\": {\"destination\": \"Tokyo\"}}", 1);
        llm(wire, "You route travel requests", "Here it is: {\"agents\": [\"flight\",\"hotel\",\"weather\",\"budget\","
                + "\"itinerary\"], \"clarification\": null, \"query\": \"7 day Tokyo trip from Delhi\", "
                + "\"trip_constraints\": {\"destination\": \"Tokyo\", \"days\": 7, \"budget\": null}, "
                + "\"reasoning\": \"full trip\"}", 5);
        // agents
        llm(wire, "List up to 3 airlines", "[\"Thai Airways\", \"Emirates\"]", 5);
        llm(wire, "You are a travel flight expert", "FLIGHT-GUIDANCE", 5);
        llm(wire, "Extract the destination as ONE city name", "Tokyo", 5);
        llm(wire, "Analyse the budget for this trip", "BUDGET-TABLE", 5);
        llm(wire, "Create a complete travel itinerary", "ITINERARY-V1", 5);
        llm(wire, "Write the final travel plan", "FINAL-ANSWER", 5);
        llm(wire, "Answer the user's question using ONLY", "FINAL-ANSWER", 5);   // a one-agent plan
        llm(wire, "Update the summary of a travel-planning chat", "SUMMARY-STUB", 5);

        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"pagination": {"limit": 30}, "data": [
                          {"flight_status": "scheduled",
                           "departure": {"airport": "Suvarnabhumi", "scheduled": "2026-11-10T10:00:00+00:00",
                                         "terminal": "1", "gate": "A3", "delay": null},
                           "arrival": {"airport": "Narita", "scheduled": "2026-11-10T18:00:00+00:00", "delay": 5},
                           "airline": {"name": "Thai Airways"}, "flight": {"number": "921", "iata": "TG921"}}]}
                        """)));
        wire.stubFor(get(urlPathEqualTo("/aviation/flights")).atPriority(1)
                .withQueryParam("airline_name", equalTo("Emirates"))
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"code\": \"function_access_restricted\", \"message\": \"no\"}}")));

        wire.stubFor(post(urlEqualTo("/tavily/search")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"results": [
                          {"title": "Grand Tokyo Hotel", "url": "https://example.com/grand",
                           "content": "Grand Tokyo Hotel in Shinjuku costs 150 USD per night with a pool and breakfast."},
                          {"title": "Budget Hostel", "url": "https://example.com/hostel",
                           "content": "A cheap hostel near Ueno station, 30 USD per night, shared rooms and free wifi."}]}
                        """)));

        wire.stubFor(get(urlPathEqualTo("/weather/weather")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"name": "Tokyo", "main": {"temp": 18.5, "feels_like": 17.0, "humidity": 60},
                         "weather": [{"description": "clear sky"}], "wind": {"speed": 3.2}}
                        """)));
        wire.stubFor(get(urlPathEqualTo("/weather/forecast")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"list": [{"dt_txt": "2026-11-11 00:00:00", "main": {"temp": 17.0},
                                   "weather": [{"description": "light rain"}]}]}
                        """)));
    }
}
