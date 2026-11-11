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

/** OpenWeather: current weather and the next five forecast entries, returned as JSON text. */
@Component
public class WeatherClient {

    record Current(String city, double temperatureC, double feelsLikeC, int humidity, String condition,
                   double windSpeed) {
    }

    record ForecastEntry(String datetime, double temperature, String weather) {
    }

    record Forecast(String city, List<ForecastEntry> forecast) {
    }

    private final AppProperties.Http config;
    private final RestClient rest;
    private final JsonMapper json;

    public WeatherClient(AppProperties props, JsonMapper json) {
        this.config = props.openweather();
        this.rest = HttpClients.create(config.baseUrl(), config.connectTimeout(), config.readTimeout());
        this.json = json;
    }

    /** An unknown city (404) is returned as the error body, like the Python tool did. */
    public String current(String city) {
        JsonNode data = get("/weather", city);
        if (!data.has("main")) {
            return data.toString();
        }
        Current current = new Current(data.path("name").asString(""), data.path("main").path("temp").asDouble(),
                data.path("main").path("feels_like").asDouble(), data.path("main").path("humidity").asInt(),
                data.path("weather").path(0).path("description").asString(""), data.path("wind").path("speed").asDouble());
        return json.writeValueAsString(current);
    }

    public String forecast(String city) {
        JsonNode data = get("/forecast", city);
        if (!data.has("list")) {
            return data.toString();
        }
        List<ForecastEntry> entries = new ArrayList<>();
        for (JsonNode item : data.path("list")) {
            if (entries.size() == 5) {
                break;
            }
            entries.add(new ForecastEntry(item.path("dt_txt").asString(""), item.path("main").path("temp").asDouble(),
                    item.path("weather").path(0).path("description").asString("")));
        }
        return json.writeValueAsString(new Forecast(city, entries));
    }

    private JsonNode get(String path, String city) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ExternalServiceException("openweather", "API key is not configured");
        }
        JsonNode body = HttpClients.callWithRetry("openweather", () -> rest.get()
                .uri(path + "?q={city}&appid={key}&units=metric", city, config.apiKey())
                .retrieve().onStatus(status -> status.is4xxClientError() && status.value() != 429, (req, res) -> { })
                .body(JsonNode.class));
        if (body == null) {
            throw new ExternalServiceException("openweather", "empty response");
        }
        return body;
    }
}
