package com.rpl.rama.jdbc;

import com.rpl.rama.integration.ExternalDepot;
import com.rpl.rama.integration.TaskGlobalContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JdbcExternalDepot2 implements ExternalDepot {

  private static final String ID_FIELD = "change_id";
  private static final String OPERATION_FIELD = "operation";
  private static final String NEW_DATA_FIELD = "new_data";
  private final String jdbcUrl;
  private final String username;
  private final String password;
  private final String tableName;
  private final int defaultFetchSize;
  private final int maxPoolSize;
  private final String idField = ID_FIELD;
  private final String operationField = OPERATION_FIELD;
  private final String newDataField = NEW_DATA_FIELD;
  private final String selectClauseForFetching;

  private HikariDataSource dataSource;
  private ExecutorService executorService;

  public JdbcExternalDepot2(String jdbcUrl, String username, String password, String tableName, int defaultFetchSize) {
    this(jdbcUrl, username, password, tableName, defaultFetchSize, 10);
  }

  public JdbcExternalDepot2(String jdbcUrl, String username, String password, String tableName, int defaultFetchSize, int maxPoolSize) {
    this.jdbcUrl = jdbcUrl;
    this.username = username;
    this.password = password;
    this.tableName = tableName;
    this.defaultFetchSize = defaultFetchSize;
    this.maxPoolSize = maxPoolSize;

    selectClauseForFetching = "SELECT " + this.idField + ", " + this.operationField + ", " + this.newDataField + " FROM " + tableName;
  }

  private void validatePartitionIndex(int partitionIndex) {
    if (partitionIndex != 0) {
      throw new IllegalArgumentException("Only partition 0 is supported");
    }
  }

  private CompletableFuture<Long> executeQueryForLong(String sql, Object... params) {
    return CompletableFuture.supplyAsync(() -> {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

          for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
          }

          try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
              return rs.getLong(1);
            }
            return 0L;
          }

        } catch (SQLException e) {
          throw new RuntimeException("Failed to execute query: " + sql, e);
        }
      }, executorService);
  }

  private CompletableFuture<List> executeQueryForRecords(String sql, Object... params) {
    return CompletableFuture.supplyAsync(() -> {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

          for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
          }

          List<Object> records = new ArrayList<>();

          try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
              AuditRecord record = new AuditRecord(rs.getLong(this.idField),
                                                   SqlOperation.fromString(rs.getString(this.operationField)),
                                                   rs.getString(this.newDataField));
              records.add(record);
            }
          }

          return records;

        } catch (SQLException e) {
          throw new RuntimeException("Failed to execute query: " + sql, e);
        }
      }, executorService);
  }
  
  // TaskGlobalObject methods
  @Override
  public void prepareForTask(int taskIndex, TaskGlobalContext taskGlobalContext) {
    // Initialize connection pool and executor service for async operations
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(maxPoolSize);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(30000);
    config.setIdleTimeout(600000);
    config.setMaxLifetime(1800000);
    config.setLeakDetectionThreshold(60000);

    this.dataSource = new HikariDataSource(config);
    this.executorService = Executors.newFixedThreadPool(maxPoolSize);
  }

  @Override
  public void gainedLeadership() {
    // No special leadership handling needed for this implementation
  }

  // ExternalDepot methods
  @Override
  public CompletableFuture<Integer> getNumPartitions() {
    // Since we assume only 1 partition
    return CompletableFuture.completedFuture(1);
  }

  @Override
  public CompletableFuture<Long> startOffset(int partitionIndex) {
    validatePartitionIndex(partitionIndex);
    String sql = "SELECT COALESCE(MIN(" + this.idField + "), 0) FROM " + tableName;
    return executeQueryForLong(sql);
  }

  @Override
  public CompletableFuture<Long> endOffset(int partitionIndex) {
    validatePartitionIndex(partitionIndex);
    String sql = "SELECT COALESCE(MAX(" + this.idField + "), -1) + 1 FROM " + tableName;
    return executeQueryForLong(sql);
  }


  @Override
  public CompletableFuture<Long> offsetAfterTimestampMillis(int partitionIndex, long millis) {
    // consuming topologies should not use startFromOffsetAfterTimestamp, for now
    return CompletableFuture.completedFuture(0L);
  }


  @Override
  public CompletableFuture<List> fetchFrom(int partitionIndex, long startOffset, long endOffset) {
    validatePartitionIndex(partitionIndex);

    if (startOffset >= endOffset) {
      return CompletableFuture.completedFuture(new ArrayList<>());
    }

    String sql = this.selectClauseForFetching +
      " WHERE " + this.idField + " >= ? AND " + this.idField + " < ? ORDER BY " + this.idField;
    return executeQueryForRecords(sql, startOffset, endOffset);
  }

  @Override
  public CompletableFuture<List> fetchFrom(int partitionIndex, long startOffset) {
    validatePartitionIndex(partitionIndex);

    String sql = selectClauseForFetching +
      " WHERE " + this.idField + " >= ? ORDER BY " + this.idField + " LIMIT " + this.defaultFetchSize;
    return executeQueryForRecords(sql, startOffset);
  }

  // AutoCloseable and Closeable implementation
  @Override
  public void close() throws IOException {
    closeResources();
  }

  private void closeResources() throws IOException {
    IOException lastException = null;

    if (executorService != null && !executorService.isShutdown()) {
      try {
        executorService.shutdown();
      } catch (Exception e) {
        lastException = new IOException("Failed to shutdown executor service", e);
      }
    }

    if (dataSource != null && !dataSource.isClosed()) {
      try {
        dataSource.close();
      } catch (Exception e) {
        IOException currentException = new IOException("Failed to close data source", e);
        if (lastException != null) {
          currentException.addSuppressed(lastException);
        }
        lastException = currentException;
      }
    }

    if (lastException != null) {
      throw lastException;
    }
  }

  public enum SqlOperation {
    INSERT("INSERT"),
    UPDATE("UPDATE"),
    DELETE("DELETE");

    private final String operation;

    // Enum constructor (must be private or package-private)
    SqlOperation(String operation) {
      this.operation = operation;
    }

    public String getOperation() {
      return operation;
    }

    // Static factory method to get enum from string (case-insensitive)
    public static SqlOperation fromString(String input) {
      for (SqlOperation op : SqlOperation.values()) {
        if (op.operation.equalsIgnoreCase(input)) {
          return op;
        }
      }
      throw new IllegalArgumentException("Unknown operation: " + input);
    }
  }

  /**
   * Simple record class to represent a PostgreSQL record //
   */
  public static class AuditRecord {
    private final long id;
    private final SqlOperation operation;
    private final String data;

    public AuditRecord(long id, SqlOperation op, String data) {
      this.id = id;
      this.operation = op;
      this.data = data;
    }

    public long getId() {
      return id;
    }

    public SqlOperation getOperation() {
      return operation;
    }

    public String getData() {
      return data;
    }

    @Override
    public String toString() {
      return "AuditRecord{id=" + id + ", operation=" + operation + ", data='" + data + "'}";
    }

  }
}
