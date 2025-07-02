package com.rpl.rama.jdbc;

import com.rpl.rama.integration.ExternalDepot;
import com.rpl.rama.integration.TaskGlobalContext;
import com.rpl.rama.integration.WorkerManagedResource;
import com.rpl.rama.ops.RamaFunction0;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public class JdbcExternalDepotV3 implements ExternalDepot {

  private static final String ID_FIELD = "change_id";
  private static final String OPERATION_FIELD = "operation";
  private static final String NEW_DATA_FIELD = "new_data";
  private final String jdbcUrl;
  private final String tableName;
  private final int defaultFetchSize;
  private final int maxPoolSize;
  private final String idField = ID_FIELD;
  private final String operationField = OPERATION_FIELD;
  private final String newDataField = NEW_DATA_FIELD;
  private final String selectClauseForFetching;

  private ExecutorService executorService;

  //TODO: support either worker or task managed resource
  WorkerManagedResource<JdbcConnectionResources> _jdbcDataSource;

  private static class JdbcConnectionResources implements Closeable {
    public ExecutorService executorService;
    public Connection conn;

    public JdbcConnectionResources(String url) throws SQLException {
      this.executorService = new ScheduledThreadPoolExecutor(1);
      this.conn = DriverManager.getConnection(url);
    }

    public void close() throws IOException {
      executorService.shutdown();
      try {
        conn.close();
      } catch (SQLException e){
        throw new IOException(e);
      }
    }
  }

  private CompletableFuture runOnJdbcThread(RamaFunction0 fn) {
    final CompletableFuture ret = new CompletableFuture();
    getDataSource().executorService.submit(() -> {
      try {
        ret.complete(fn.invoke());
      } catch (Throwable t) {
        ret.completeExceptionally(t);
      }
    });
    return ret;
  }

  public JdbcExternalDepotV3(String jdbcUrl, String tableName, int defaultFetchSize) {
    this(jdbcUrl, tableName, defaultFetchSize, 10);
  }

  public JdbcExternalDepotV3(String jdbcUrl, String tableName, int defaultFetchSize, int maxPoolSize) {
    this.jdbcUrl = jdbcUrl;
    this.tableName = tableName;
    this.defaultFetchSize = defaultFetchSize;
    this.maxPoolSize = maxPoolSize;

    //TODO: make this parametric
    selectClauseForFetching = "SELECT " + this.idField + ", " + this.operationField + ", " + this.newDataField + " FROM " + tableName;
  }

  private void validatePartitionIndex(int partitionIndex) {
    if (partitionIndex != 0) {
      throw new IllegalArgumentException("Only partition 0 is supported");
    }
  }

  private CompletableFuture<Long> executeQueryForLong(String sql, Object... params) {
    return runOnJdbcThread(
        () -> {
          try (PreparedStatement stmt = getDataSource().conn.prepareStatement(sql)) {

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
      });
  }

  private CompletableFuture<List> executeQueryForRecords(String sql, Object... params) {
    return runOnJdbcThread(() -> {
        try (PreparedStatement stmt = getDataSource().conn.prepareStatement(sql)) {

          for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
          }

          List<AuditRecord> records = new ArrayList<>();

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
      });
  }

  public JdbcConnectionResources getDataSource() {
    return _jdbcDataSource.getResource();
  }

  // TaskGlobalObject methods
  @Override
  public void prepareForTask(int taskIndex, TaskGlobalContext context) {
    _jdbcDataSource = new WorkerManagedResource("datasource", context, () -> {
        return new JdbcConnectionResources(jdbcUrl);
    });
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
    throw new UnsupportedOperationException();
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
    _jdbcDataSource.close();
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
