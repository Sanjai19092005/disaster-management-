package com.disaster.app.config;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class FusekiInitializer implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(FusekiInitializer.class);

    @Value("${jena.fuseki.admin-url}")
    private String adminUrl;

    @Value("${jena.fuseki.dataset-name}")
    private String datasetName;

    @Value("${jena.fuseki.query-url}")
    private String queryUrl;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        logger.info("Initializing Apache Jena Fuseki connection...");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        boolean connected = false;
        int retries = 15;
        while (!connected && retries > 0) {
            try {
                // Check if Fuseki is reachable by calling info endpoint
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(adminUrl.replace("/$/datasets", "/$/server")))
                        .header("Authorization", "Basic YWRtaW46YWRtaW4=") // admin:admin
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    connected = true;
                    logger.info("Successfully connected to Apache Jena Fuseki server!");
                } else {
                    logger.warn("Fuseki server returned status {}. Retrying...", response.statusCode());
                }
            } catch (Exception e) {
                logger.warn("Fuseki server not reachable: {}. Retrying in 2 seconds...", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                retries--;
            }
        }

        if (!connected) {
            logger.error("Failed to connect to Apache Jena Fuseki server after multiple retries. Exiting initialization.");
            return;
        }

        // Check if dataset exists, and create if not
        try {
            HttpRequest checkRequest = HttpRequest.newBuilder()
                    .uri(URI.create(adminUrl + "/" + datasetName))
                    .header("Authorization", "Basic YWRtaW46YWRtaW4=")
                    .GET()
                    .build();

            HttpResponse<String> checkResponse = client.send(checkRequest, HttpResponse.BodyHandlers.ofString());
            if (checkResponse.statusCode() == 404) {
                logger.info("Dataset '{}' does not exist. Creating dataset...", datasetName);
                
                // Create dataset via POST
                String form = "dbName=" + datasetName + "&dbType=mem";
                HttpRequest createRequest = HttpRequest.newBuilder()
                        .uri(URI.create(adminUrl))
                        .header("Authorization", "Basic YWRtaW46YWRtaW4=")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();

                HttpResponse<String> createResponse = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
                if (createResponse.statusCode() == 200 || createResponse.statusCode() == 201) {
                    logger.info("Dataset '{}' created successfully!", datasetName);
                    loadInitialData();
                } else {
                    logger.error("Failed to create dataset '{}'. Status code: {}, Response: {}", 
                            datasetName, createResponse.statusCode(), createResponse.body());
                }
            } else if (checkResponse.statusCode() == 200) {
                logger.info("Dataset '{}' already exists. Loading/refreshing data...", datasetName);
                loadInitialData();
            } else {
                logger.warn("Unexpected status code checking dataset: {}", checkResponse.statusCode());
            }
        } catch (Exception e) {
            logger.error("Error creating or checking dataset: {}", e.getMessage(), e);
        }
    }

    private void loadInitialData() {
        try {
            logger.info("Loading ontology and sample data into Fuseki default graph...");

            Model model = ModelFactory.createDefaultModel();

            // Load ontology
            try (InputStream ontIn = getClass().getResourceAsStream("/ontology.ttl")) {
                if (ontIn == null) {
                    throw new RuntimeException("ontology.ttl not found in classpath");
                }
                RDFDataMgr.read(model, ontIn, Lang.TURTLE);
                logger.info("Ontology definitions loaded into Jena Model.");
            }

            // Load sample data
            try (InputStream dataIn = getClass().getResourceAsStream("/data.ttl")) {
                if (dataIn == null) {
                    throw new RuntimeException("data.ttl not found in classpath");
                }
                RDFDataMgr.read(model, dataIn, Lang.TURTLE);
                logger.info("Sample data triples loaded into Jena Model.");
            }

            // Upload using RDFConnection
            // Connect to dataset query-url base (without /query)
            String connectionUrl = queryUrl.substring(0, queryUrl.lastIndexOf("/"));
            logger.info("Uploading Model to Fuseki endpoint: {}", connectionUrl);
            
            try (RDFConnection conn = RDFConnection.connect(connectionUrl)) {
                // Clear existing dataset elements and load new model
                conn.put(model);
                logger.info("Successfully uploaded {} triples to Fuseki default graph!", model.size());
            }

        } catch (Exception e) {
            logger.error("Failed to load initial ontology or data: {}", e.getMessage(), e);
        }
    }
}
