package com.rpl.rama.jdbc;

import com.rpl.rama.integration.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class JdbcExternalDepot implements ExternalDepot {
  TaskGlobalContext _context;

  @Override
  public void prepareForTask(int taskId, TaskGlobalContext context) {
    _context = context;
  }

  @Override
  public void close() throws IOException {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'close'");
  }

  @Override
  public CompletableFuture<Long> endOffset(int partitionIndex) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'endOffset'");
  }

  @Override
  public CompletableFuture<List> fetchFrom(int partitionIndex, long startOffset) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'fetchFrom'");
  }

  @Override
  public CompletableFuture<List> fetchFrom(int partitionIndex, long startOffset, long endOffset) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'fetchFrom'");
  }

  @Override
  public CompletableFuture<Integer> getNumPartitions() {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'getNumPartitions'");
  }

  @Override
  public CompletableFuture<Long> offsetAfterTimestampMillis(int partitionIndex, long millis) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'offsetAfterTimestampMillis'");
  }

  @Override
  public CompletableFuture<Long> startOffset(int partitionIndex) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'startOffset'");
  }
}
