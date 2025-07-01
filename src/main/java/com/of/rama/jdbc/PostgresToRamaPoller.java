package com.of.rama.jdbc;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresToRamaPoller {
    private static final Logger logger = LoggerFactory.getLogger(PostgresToRamaPoller.class);

    // TODO: load from environment
    private static final String DB_PORT = System.getenv("JAVA_HOME");
    private static final String DB_URL = "jdbc:postgresql://localhost:" + DB_PORT + "/rama_jdbc_db";
    private static final String DB_USER = "rama_jdbc_user";
    private static final String DB_PASSWORD = "";

    // Polling configuration
    private static final int POLL_INTERVAL_MS = 1000; // 1 second
    private static final int BATCH_SIZE = 4;

    private static final String LAST_CHANGE_ID_FILE = "last_change_id.txt";

    // Tracks the last processed change_id
    private long lastChangeId;

    public PostgresToRamaPoller() {
        // Load lastChangeId from file on startup
        loadLastChangeId();

        // Register shutdown hook to save lastChangeId
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveLastChangeId, "ShutdownHook"));
    }

    private void loadLastChangeId() {
        try {
            if (Files.exists(Paths.get(LAST_CHANGE_ID_FILE))) {
                String content = Files.readString(Paths.get(LAST_CHANGE_ID_FILE)).trim();
                lastChangeId = Long.parseLong(content);
                logger.info("Loaded lastChangeId: {} from {}", lastChangeId, LAST_CHANGE_ID_FILE);
            } else {
                lastChangeId = 0;
                logger.info("No lastChangeId file found, starting with lastChangeId: 0");
            }
        } catch (IOException | NumberFormatException e) {
            logger.error("Error loading lastChangeId from {}, starting with 0", LAST_CHANGE_ID_FILE, e);
            lastChangeId = 0;
        }
    }

    private void saveLastChangeId() {
        try {
            Files.writeString(Paths.get(LAST_CHANGE_ID_FILE), String.valueOf(lastChangeId));
            logger.info("Saved lastChangeId: {} to {}", lastChangeId, LAST_CHANGE_ID_FILE);
        } catch (IOException e) {
            logger.error("Error saving lastChangeId to {}", LAST_CHANGE_ID_FILE, e);
        }
    }

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", DB_USER);
        props.setProperty("password", DB_PASSWORD);
        // Optional: Configure SSL or other connection properties
        // props.setProperty("ssl", "true");
        return DriverManager.getConnection(DB_URL, props);
    }

    private void pollChanges() {
        String query = "SELECT change_id, operation, old_data, new_data, change_timestamp " +
                      "FROM users_cdc WHERE change_id > ? ORDER BY change_id LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setLong(1, lastChangeId);
            stmt.setInt(2, BATCH_SIZE);

            try (ResultSet rs = stmt.executeQuery()) {
                int recordCount = 0;
                while (rs.next()) {
                    recordCount++;
                    long changeId = rs.getLong("change_id");
                    String operation = rs.getString("operation");
                    String oldData = rs.getString("old_data");
                    String newData = rs.getString("new_data");
                    java.sql.Timestamp timestamp = rs.getTimestamp("change_timestamp");

                    // Process the record (e.g., append to Rama depot)
                    processChangeRecord(changeId, operation, oldData, newData, timestamp);

                    // Update the last processed change_id
                    lastChangeId = changeId;
                }
                logger.info("Processed {} records in this batch", recordCount);
            }
        } catch (SQLException e) {
            logger.error("Error polling users_cdc table", e);
            // Implement retry logic
            try {
                Thread.sleep(5000); // Wait before retrying
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted during retry delay", ie);
            }
        }
    }

    private void processChangeRecord(long changeId, String operation, String oldData,
                                   String newData, java.sql.Timestamp timestamp) {
        // Placeholder for Rama depot integration
        // Replace with actual Rama depot append logic
        logger.info("Processing change_id: {}, operation: {}, timestamp: {}",
                    changeId, operation, timestamp);
        logger.debug("Old data: {}, New data: {}", oldData, newData);

        // Example Rama integration (uncomment and customize):
        /*
        try {
            Depot depot = RamaClusterManager.open("zookeeper-connect-string")
                           .getDepot("module-name", "depot-name");
            depot.append(new ChangeRecord(operation, newData));
        } catch (Exception e) {
            logger.error("Error appending to Rama depot", e);
        }
        */
    }

    public void startPolling() {
        logger.info("Starting Postgres to Rama poller with initial lastChangeId: {}", lastChangeId);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollChanges();
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Polling interrupted", e);
                break;
            }
        }
        // Save lastChangeId on normal termination
        saveLastChangeId();
        logger.info("Poller stopped");
    }

    public static void main(String[] args) {
        PostgresToRamaPoller poller = new PostgresToRamaPoller();
        poller.startPolling();
    }

    // Optional: Class to represent a change record for Rama
    static class ChangeRecord {
        String operation;
        String data;

        ChangeRecord(String operation, String data) {
            this.operation = operation;
            this.data = data;
        }
    }

}
