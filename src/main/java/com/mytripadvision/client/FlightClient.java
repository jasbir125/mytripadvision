package com.mytripadvision.client;

import com.mytripadvision.config.HttpClients;
import com.mytripadvision.config.AppProperties;
import com.mytripadvision.exception.ExternalServiceException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Live flights from AviationStack. The free plan allows only GET /flights (the other endpoints are restricted). */
@Component
public class FlightClient {

    /** What we keep of one flight. */
    record Flight(String flightNumber, String airline, String status,
                  String departureAirport, String departureScheduled, int departureDelay,
                  String departureTerminal, String departureGate,
                  String arrivalAirport, String arrivalScheduled, int arrivalDelay,
                  String arrivalTerminal, String arrivalGate) {
    }

    private final AppProperties.Http config;
    private final RestClient rest;
    private final JsonMapper json;

    public FlightClient(AppProperties props, JsonMapper json) {
        this.config = props.aviationstack();
        this.rest = HttpClients.create(config.baseUrl(), config.connectTimeout(), config.readTimeout());
        this.json = json;
    }

    /**
     * @return the airline's flights as JSON text
     * @throws ExternalServiceException when the call fails or AviationStack answers with an error body
     */
    public String flightsForAirline(String airlineName, int limit) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ExternalServiceException("aviationstack", "API key is not configured");
        }
        JsonNode body = HttpClients.callWithRetry("aviationstack", () -> rest.get()
                .uri("/flights?access_key={key}&airline_name={airline}&limit={limit}",
                        config.apiKey(), airlineName, limit)
                // Every 4xx carries AviationStack's {"error": {"code": ...}} body, which we read below.
                // That includes 429 "usage_limit_reached" (monthly quota): retrying cannot help, so we
                // do not let RestClient throw for it. We want the reason in the log instead of "HTTP 429".
                .retrieve().onStatus(status -> status.is4xxClientError(), (req, res) -> { })
                .body(JsonNode.class));
        if (body == null) {
            throw new ExternalServiceException("aviationstack", "empty response");
        }
        // error body: {"error": {"code": "function_access_restricted", "type": "..."}}
        if (body.has("error")) {
            String reason = body.path("error").path("type").asString(body.path("error").path("code").asString("error"));
            throw new ExternalServiceException("aviationstack", reason);
        }
        List<Flight> flights = new ArrayList<>();
        for (JsonNode item : body.path("data")) {
            flights.add(toFlight(item));
        }
        return json.writeValueAsString(flights);
    }

    private Flight toFlight(JsonNode f) {
        JsonNode dep = f.path("departure");
        JsonNode arr = f.path("arrival");
        String number = f.path("flight").path("iata").asString(f.path("flight").path("number").asString(""));
        return new Flight(number, f.path("airline").path("name").asString(""), f.path("flight_status").asString(""),
                dep.path("airport").asString(""), dep.path("scheduled").asString(""), dep.path("delay").asInt(0),
                dep.path("terminal").asString(""), dep.path("gate").asString(""),
                arr.path("airport").asString(""), arr.path("scheduled").asString(""), arr.path("delay").asInt(0),
                arr.path("terminal").asString(""), arr.path("gate").asString(""));
    }
}
