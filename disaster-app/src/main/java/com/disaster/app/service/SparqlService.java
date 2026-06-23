package com.disaster.app.service;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SparqlService {

    private static final Logger logger = LoggerFactory.getLogger(SparqlService.class);

    @Value("${jena.fuseki.query-url}")
    private String queryUrl;

    @Value("${jena.fuseki.update-url}")
    private String updateUrl;

    public List<Map<String, String>> executeQuery(String sparqlQuery) {
        logger.info("Executing SPARQL Query:\n{}", sparqlQuery);
        List<Map<String, String>> resultsList = new ArrayList<>();
        
        try (QueryExecution qexec = QueryExecutionFactory.sparqlService(queryUrl, sparqlQuery)) {
            ResultSet results = qexec.execSelect();
            List<String> varNames = results.getResultVars();
            
            while (results.hasNext()) {
                QuerySolution soln = results.nextSolution();
                Map<String, String> row = new HashMap<>();
                for (String varName : varNames) {
                    RDFNode node = soln.get(varName);
                    if (node != null) {
                        if (node.isURIResource()) {
                            row.put(varName, node.asResource().getURI());
                        } else if (node.isLiteral()) {
                            row.put(varName, node.asLiteral().getLexicalForm());
                        } else {
                            row.put(varName, node.toString());
                        }
                    } else {
                        row.put(varName, "");
                    }
                }
                resultsList.add(row);
            }
        } catch (Exception e) {
            logger.error("Error executing SPARQL query: {}", e.getMessage(), e);
            throw new RuntimeException("SPARQL execution failed: " + e.getMessage());
        }
        return resultsList;
    }

    public void executeUpdate(String sparqlUpdate) {
        logger.info("Executing SPARQL Update:\n{}", sparqlUpdate);
        String datasetUrl = updateUrl.substring(0, updateUrl.lastIndexOf("/"));
        try (org.apache.jena.rdfconnection.RDFConnection conn = org.apache.jena.rdfconnection.RDFConnectionFactory.connectPW(datasetUrl, "admin", "admin")) {
            UpdateRequest request = UpdateFactory.create(sparqlUpdate);
            conn.update(request);
            logger.info("SPARQL Update executed successfully.");
        } catch (Exception e) {
            logger.error("Error executing SPARQL update: {}", e.getMessage(), e);
            throw new RuntimeException("SPARQL update failed: " + e.getMessage());
        }
    }
}
