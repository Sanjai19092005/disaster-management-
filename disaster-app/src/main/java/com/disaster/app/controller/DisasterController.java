package com.disaster.app.controller;

import com.disaster.app.service.DisasterService;
import com.disaster.app.service.SparqlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DisasterController {

    private static final Logger logger = LoggerFactory.getLogger(DisasterController.class);

    @Autowired
    private SparqlService sparqlService;

    @Autowired
    private DisasterService disasterService;

    /**
     * Executes custom SPARQL query.
     */
    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("SPARQL query cannot be empty");
        }
        try {
            List<Map<String, String>> results = sparqlService.executeQuery(query);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * Retrieves all graph nodes and edges for Cytoscape.js visualization.
     */
    @GetMapping("/graph")
    public ResponseEntity<Map<String, Object>> getGraph() {
        return ResponseEntity.ok(disasterService.getGraphData());
    }

    /**
     * Aggregated metrics for dashboard analytics.
     */
    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics() {
        return ResponseEntity.ok(disasterService.getAnalytics());
    }

    /**
     * Retrieves resource and agency recommendations for a specific disaster.
     */
    @GetMapping("/recommendations")
    public ResponseEntity<List<Map<String, Object>>> getRecommendations(@RequestParam("disaster") String disasterUri) {
        return ResponseEntity.ok(disasterService.getRecommendations(disasterUri));
    }

    /**
     * Computes the shortest path using Dijkstra, avoiding flooded/blocked roads if requested.
     */
    @GetMapping("/routes")
    public ResponseEntity<Map<String, Object>> getRoute(
            @RequestParam("start") String startUri,
            @RequestParam("end") String endUri,
            @RequestParam(value = "avoidBlocked", defaultValue = "true") boolean avoidBlocked) {
        return ResponseEntity.ok(disasterService.findShortestRoute(startUri, endUri, avoidBlocked));
    }

    /**
     * Run disaster prediction based on inputs and insert the resulting risk into the graph.
     */
    @PostMapping("/predict")
    public ResponseEntity<?> predictAndSave(@RequestBody Map<String, Object> payload) {
        try {
            String cityUri = (String) payload.get("city");
            double rainfall = Double.parseDouble(payload.get("rainfall").toString());
            double seismic = Double.parseDouble(payload.get("seismicActivity").toString());
            double waterLevel = Double.parseDouble(payload.get("waterLevel").toString());

            if (cityUri == null || cityUri.isEmpty()) {
                return ResponseEntity.badRequest().body("City URI is required");
            }

            String riskLevel = "Low";
            String cause = "All environmental indicators are within safe thresholds.";

            if (seismic >= 5.5) {
                riskLevel = "High";
                cause = String.format("Seismic activity is critically high (%.1f g-force). Potential major earthquake warning.", seismic);
            } else if (rainfall >= 150.0 && waterLevel >= 4.5) {
                riskLevel = "High";
                cause = String.format("Extreme rainfall (%.1f mm) and critical water levels (%.1f m) pose an imminent flood threat.", rainfall, waterLevel);
            } else if (seismic >= 3.5) {
                riskLevel = "Medium";
                cause = String.format("Moderate seismic tremors detected (%.1f g-force). Monitoring recommended.", seismic);
            } else if (rainfall >= 80.0 && waterLevel >= 3.0) {
                riskLevel = "Medium";
                cause = String.format("Elevated rainfall (%.1f mm) and rising water levels (%.1f m). Risk of localized waterlogging.", rainfall, waterLevel);
            }

            Map<String, Object> result = disasterService.savePredictionRisk(cityUri, riskLevel, cause);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * Mock weather alerts API.
     */
    @GetMapping("/weather")
    public ResponseEntity<Map<String, Object>> getWeather(@RequestParam(value = "city", defaultValue = "Chennai") String city) {
        Map<String, Object> weather = new HashMap<>();
        weather.put("city", city);

        if (city.equalsIgnoreCase("Chennai")) {
            weather.put("temperature", "29°C");
            weather.put("condition", "Heavy Thunderstorms");
            weather.put("humidity", "92%");
            weather.put("windSpeed", "24 km/h");
            weather.put("alertLevel", "Orange");
            weather.put("alertMessage", "Severe rainfall warning for the next 24 hours. High risk of localized coastal flooding.");
        } else if (city.equalsIgnoreCase("Mumbai")) {
            weather.put("temperature", "28°C");
            weather.put("condition", "Moderate Monsoonal Rain");
            weather.put("humidity", "88%");
            weather.put("windSpeed", "18 km/h");
            weather.put("alertLevel", "Yellow");
            weather.put("alertMessage", "Continuous light-to-moderate rain. Watch for low-lying water accumulations.");
        } else {
            weather.put("temperature", "31°C");
            weather.put("condition", "Partly Cloudy");
            weather.put("humidity", "65%");
            weather.put("windSpeed", "10 km/h");
            weather.put("alertLevel", "Green");
            weather.put("alertMessage", "No current weather warnings active.");
        }

        return ResponseEntity.ok(weather);
    }
}
