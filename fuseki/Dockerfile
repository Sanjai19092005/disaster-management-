FROM stain/jena-fuseki:4.0.0

# Copy custom shiro.ini to FUSEKI_BASE (/fuseki)
COPY shiro.ini /fuseki/shiro.ini

# Ensure correct permissions
USER root
RUN chmod 644 /fuseki/shiro.ini

# Return to standard fuseki user (if any, stain/jena-fuseki runs as root or fuseki depending on setup, but typically root)
