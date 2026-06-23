package com.disaster.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DisasterService {

    private static final Logger logger = LoggerFactory.getLogger(DisasterService.class);

    @Autowired
    private SparqlService sparqlService;

    // Helper to get local name from URI
    public String getLocalName(String uri) {
        if (uri == null) return "";
        int hashIdx = uri.indexOf('#');
        if (hashIdx != -1) {
            return uri.substring(hashIdx + 1);
        }
        int slashIdx = uri.lastIndexOf('/');
        if (slashIdx != -1) {
            return uri.substring(slashIdx + 1);
        }
        return uri;
    }

    /**
     * Retrieves all graph nodes and edges for Cytoscape.js visualization.
     */
    public Map<String, Object> getGraphData() {
        String query = "SELECT ?s ?p ?o WHERE { ?s ?p ?o }";
        List<Map<String, String>> triples = sparqlService.executeQuery(query);

        Map<String, Map<String, Object>> nodesMap = new HashMap<>();
        List<Map<String, Object>> edgesList = new ArrayList<>();

        for (Map<String, String> triple : triples) {
            String s = triple.get("s");
            String p = triple.get("p");
            String o = triple.get("o");

            String sLocal = getLocalName(s);
            String pLocal = getLocalName(p);
            String oLocal = getLocalName(o);

            // Skip schema-level metadata details to keep visualization clean
            if (s.startsWith("http://www.w3.org") || p.startsWith("http://www.w3.org") || 
                (o != null && o.startsWith("http://www.w3.org") && !pLocal.equals("type"))) {
                continue;
            }

            // Ensure source node exists
            nodesMap.putIfAbsent(s, createBaseNode(s, sLocal));

            // If object is a URI (relation)
            if (o != null && (o.startsWith("http://") || o.startsWith("https://"))) {
                nodesMap.putIfAbsent(o, createBaseNode(o, oLocal));

                // Handle rdf:type to categorize node type
                if (pLocal.equals("type")) {
                    nodesMap.get(s).put("type", oLocal);
                } else {
                    // Create an edge
                    Map<String, Object> edge = new HashMap<>();
                    Map<String, String> data = new HashMap<>();
                    data.put("id", sLocal + "_" + pLocal + "_" + oLocal);
                    data.put("source", s);
                    data.put("target", o);
                    data.put("label", pLocal);
                    edge.put("data", data);
                    edgesList.add(edge);
                }
            } else if (o != null) {
                // Literal property: add it as attribute to source node
                Map<String, Object> node = nodesMap.get(s);
                @SuppressWarnings("unchecked")
                Map<String, String> properties = (Map<String, String>) node.get("properties");
                properties.put(pLocal, o);
            }
        }

        // Post-process nodes: set default type based on URI context if type is missing
        for (Map.Entry<String, Map<String, Object>> entry : nodesMap.entrySet()) {
            Map<String, Object> node = entry.getValue();
            if (node.get("type").equals("Unknown")) {
                String id = (String) node.get("id");
                if (id.contains("Hospital") || id.contains("ReliefCenter") || id.contains("Shelter")) {
                    node.put("type", "Shelter");
                } else if (id.contains("Flood") || id.contains("Earthquake") || id.contains("Cyclone") || id.contains("Wildfire") || id.contains("Disaster")) {
                    node.put("type", "Disaster");
                } else if (id.contains("City") || id.contains("District") || id.contains("State") || id.contains("Mylapore") || id.contains("Adyar") || id.contains("Bandra")) {
                    node.put("type", "Location");
                } else if (id.contains("NDRF") || id.contains("RedCross") || id.contains("Corps") || id.contains("Rescue") || id.contains("NGO")) {
                    node.put("type", "Agency");
                } else if (id.contains("Packets") || id.contains("Bottles") || id.contains("Kits")) {
                    node.put("type", "Resource");
                }
            }
        }

        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", new ArrayList<>(nodesMap.values()));
        graph.put("edges", edgesList);
        return graph;
    }

    private Map<String, Object> createBaseNode(String uri, String localName) {
        Map<String, Object> node = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("id", uri);
        data.put("label", localName);
        node.put("data", data);
        node.put("id", uri);
        node.put("label", localName);
        node.put("type", "Unknown");
        node.put("properties", new HashMap<String, String>());
        return node;
    }

    /**
     * Get aggregate statistics for dashboard metrics.
     */
    public Map<String, Object> getAnalytics() {
        Map<String, Object> stats = new HashMap<>();

        // 1. Active Disasters
        String activeDisastersQuery = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "SELECT (COUNT(?d) as ?count) WHERE { ?d a ?type . ?type rdfs:subClassOf* :Disaster }";
        List<Map<String, String>> res1 = sparqlService.executeQuery(activeDisastersQuery);
        stats.put("disasterCount", res1.isEmpty() ? 0 : Integer.parseInt(res1.get(0).get("count")));

        // 2. Total Shelters
        String sheltersQuery = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "SELECT (COUNT(?s) as ?count) (SUM(?cap) as ?totalCap) WHERE {\n" +
                "  ?s a ?type . ?type rdfs:subClassOf* :Shelter .\n" +
                "  OPTIONAL { ?s :capacity ?cap }\n" +
                "}";
        List<Map<String, String>> res2 = sparqlService.executeQuery(sheltersQuery);
        if (!res2.isEmpty()) {
            stats.put("shelterCount", Integer.parseInt(res2.get(0).get("count")));
            String totalCapStr = res2.get(0).get("totalCap");
            stats.put("shelterCapacity", totalCapStr.isEmpty() ? 0 : Integer.parseInt(totalCapStr));
        } else {
            stats.put("shelterCount", 0);
            stats.put("shelterCapacity", 0);
        }

        // 3. Total Agencies
        String agenciesQuery = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "SELECT (COUNT(?a) as ?count) WHERE { ?a a ?type . ?type rdfs:subClassOf* :Agency }";
        List<Map<String, String>> res3 = sparqlService.executeQuery(agenciesQuery);
        stats.put("agencyCount", res3.isEmpty() ? 0 : Integer.parseInt(res3.get(0).get("count")));

        // 4. Resource distribution list
        String resourcesQuery = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "SELECT ?agency ?resource WHERE {\n" +
                "  ?agency :providesResource ?resource .\n" +
                "}";
        List<Map<String, String>> res4 = sparqlService.executeQuery(resourcesQuery);
        Map<String, Integer> resourceStats = new HashMap<>();
        for (Map<String, String> row : res4) {
            String resLocal = getLocalName(row.get("resource"));
            resourceStats.put(resLocal, resourceStats.getOrDefault(resLocal, 0) + 1);
        }
        stats.put("resourceDistribution", resourceStats);

        return stats;
    }

    /**
     * Get resource and agency recommendations for a disaster.
     */
    public List<Map<String, Object>> getRecommendations(String disasterUri) {
        String query = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "SELECT DISTINCT ?resource ?agency ?agencyLocation WHERE {\n" +
                "  <" + disasterUri + "> :requiresResource ?resource .\n" +
                "  ?agency :providesResource ?resource .\n" +
                "  OPTIONAL { ?agency :locatedAt ?agencyLocation }\n" +
                "}";

        List<Map<String, String>> queryResults = sparqlService.executeQuery(query);
        Map<String, Map<String, Object>> recommendedAgencies = new HashMap<>();

        for (Map<String, String> row : queryResults) {
            String agencyUri = row.get("agency");
            String resourceUri = row.get("resource");
            String agencyLocUri = row.get("agencyLocation");

            String agencyLocal = getLocalName(agencyUri);
            String resourceLocal = getLocalName(resourceUri);
            String locationLocal = getLocalName(agencyLocUri);

            recommendedAgencies.putIfAbsent(agencyUri, new HashMap<>());
            Map<String, Object> agencyInfo = recommendedAgencies.get(agencyUri);
            agencyInfo.put("agencyName", agencyLocal);
            agencyInfo.put("agencyUri", agencyUri);
            agencyInfo.put("location", locationLocal);

            @SuppressWarnings("unchecked")
            List<String> resources = (List<String>) agencyInfo.computeIfAbsent("resourcesProvided", k -> new ArrayList<String>());
            if (!resources.contains(resourceLocal)) {
                resources.add(resourceLocal);
            }
        }

        return new ArrayList<>(recommendedAgencies.values());
    }

    /**
     * Suggests emergency routing based on RDF connectivity.
     * Computes Dijkstra shortest path on connections where status is not "Blocked" or "Flooded".
     */
    public Map<String, Object> findShortestRoute(String startLocUri, String endLocUri, boolean avoidBlocked) {
        // Query connections: ?conn :connectedTo ?loc1; :connectedTo ?loc2; :distance ?dist; :status ?status
        String query = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "SELECT ?conn ?loc1 ?loc2 ?distance ?status WHERE {\n" +
                "  ?conn :connectedTo ?loc1 .\n" +
                "  ?conn :connectedTo ?loc2 .\n" +
                "  FILTER(?loc1 != ?loc2)\n" +
                "  OPTIONAL { ?conn :distance ?distance }\n" +
                "  OPTIONAL { ?conn :status ?status }\n" +
                "}";

        List<Map<String, String>> rows = sparqlService.executeQuery(query);

        // Build Graph representation
        Map<String, List<RouteEdge>> adjacencyList = new HashMap<>();
        for (Map<String, String> row : rows) {
            String loc1 = row.get("loc1");
            String loc2 = row.get("loc2");
            double dist = row.get("distance").isEmpty() ? 1.0 : Double.parseDouble(row.get("distance"));
            String status = row.get("status").isEmpty() ? "Clear" : row.get("status");

            // Filter out blocked paths if avoidBlocked is true
            if (avoidBlocked && (status.equalsIgnoreCase("Blocked") || status.equalsIgnoreCase("Flooded"))) {
                continue;
            }

            adjacencyList.putIfAbsent(loc1, new ArrayList<>());
            adjacencyList.putIfAbsent(loc2, new ArrayList<>());

            adjacencyList.get(loc1).add(new RouteEdge(loc2, dist, status));
            adjacencyList.get(loc2).add(new RouteEdge(loc1, dist, status));
        }

        // Run Dijkstra
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        PriorityQueue<RouteNode> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.distance));

        for (String node : adjacencyList.keySet()) {
            distances.put(node, Double.MAX_VALUE);
        }

        if (!distances.containsKey(startLocUri)) {
            Map<String, Object> errorRes = new HashMap<>();
            errorRes.put("success", false);
            errorRes.put("message", "Start location not found in routing graph");
            return errorRes;
        }

        distances.put(startLocUri, 0.0);
        pq.add(new RouteNode(startLocUri, 0.0));

        while (!pq.isEmpty()) {
            RouteNode curr = pq.poll();

            if (curr.distance > distances.get(curr.nodeId)) continue;
            if (curr.nodeId.equals(endLocUri)) break; // Found shortest path

            List<RouteEdge> edges = adjacencyList.getOrDefault(curr.nodeId, Collections.emptyList());
            for (RouteEdge edge : edges) {
                double newDist = distances.get(curr.nodeId) + edge.distance;
                if (newDist < distances.get(edge.target)) {
                    distances.put(edge.target, newDist);
                    predecessors.put(edge.target, curr.nodeId);
                    pq.add(new RouteNode(edge.target, newDist));
                }
            }
        }

        if (distances.get(endLocUri) == null || distances.get(endLocUri) == Double.MAX_VALUE) {
            Map<String, Object> errorRes = new HashMap<>();
            errorRes.put("success", false);
            errorRes.put("message", "No route available between the specified locations");
            return errorRes;
        }

        // Reconstruct path
        List<String> path = new ArrayList<>();
        List<String> friendlyPath = new ArrayList<>();
        String step = endLocUri;
        while (step != null) {
            path.add(0, step);
            friendlyPath.add(0, getLocalName(step));
            step = predecessors.get(step);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("path", path);
        result.put("friendlyPath", friendlyPath);
        result.put("totalDistance", distances.get(endLocUri));
        return result;
    }

    /**
     * Inserts predicted risk as a new triple in the knowledge graph.
     */
    public Map<String, Object> savePredictionRisk(String cityUri, String riskLevel, String cause) {
        String cityLocal = getLocalName(cityUri);
        String riskUri = "http://disaster.org/ontology#" + cityLocal + "Risk";
        
        // Remove existing risks for this city if any
        String deleteQuery = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "DELETE WHERE {\n" +
                "  <" + cityUri + "> :hasPredictedRisk ?risk .\n" +
                "  ?risk ?p ?o .\n" +
                "}";
        sparqlService.executeUpdate(deleteQuery);

        // Insert new risk
        String insertQuery = 
                "PREFIX : <http://disaster.org/ontology#>\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "INSERT DATA {\n" +
                "  <" + cityUri + "> :hasPredictedRisk <" + riskUri + "> .\n" +
                "  <" + riskUri + "> a :PredictedRisk ;\n" +
                "             :riskLevel \"" + riskLevel + "\" ;\n" +
                "             :cause \"" + cause + "\" .\n" +
                "}";
        
        sparqlService.executeUpdate(insertQuery);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("city", cityLocal);
        result.put("riskLevel", riskLevel);
        result.put("cause", cause);
        result.put("riskUri", riskUri);
        return result;
    }

    private static class RouteEdge {
        String target;
        double distance;
        String status;

        RouteEdge(String target, double distance, String status) {
            this.target = target;
            this.distance = distance;
            this.status = status;
        }
    }

    private static class RouteNode {
        String nodeId;
        double distance;

        RouteNode(String nodeId, double distance) {
            this.nodeId = nodeId;
            this.distance = distance;
        }
    }
}
