# Use an official Jetty base image with Java 17
FROM jetty:jdk17

# Set environment variables for Jetty/Application
ENV JETTY_BASE /var/lib/jetty
ENV JETTY_HOME /usr/local/jetty
ENV JETTY_RUN /tmp/jetty

RUN java -jar "$JETTY_HOME/start.jar" --add-modules=http,jdbc,jndi,ee10-deploy

USER jetty

# Copy the built WAR file into Jetty's webapps directory
COPY target/tsi_ledger.war ${JETTY_BASE}/webapps/root.war

EXPOSE 8080
