package com.rpl.rama.jdbc;

import com.rpl.rama.integration.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class JdbcExternalDepot implements ExternalDepot {
  TaskGlobalContext _context;
  long start;

  public JdbcExternalDepot() {
    this.start = System.currentTimeMillis() / 1000;
  }

  @Override
  public void prepareForTask(int taskId, TaskGlobalContext context) {
    _context = context;
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public CompletableFuture<Long> endOffset(int partitionIndex) {
    return CompletableFuture.completedFuture(System.currentTimeMillis() / 1000);
  }

  @Override
  public CompletableFuture<List> fetchFrom(int partitionIndex, long startOffset) {
    long end = System.currentTimeMillis() / 1000;
    List<Long> res = new ArrayList<>();
    for (long i = startOffset; i < end; i++) {
      res.add(i);
    }
    return CompletableFuture.completedFuture(res);
  }

  @Override
  public CompletableFuture<List> fetchFrom(int partitionIndex, long startOffset, long endOffset) {
    List<Long> res = new ArrayList<>();
    for (long i = startOffset; i < endOffset; i++) {
      res.add(i);
    }
    return CompletableFuture.completedFuture(res);
  }

  @Override
  public CompletableFuture<Integer> getNumPartitions() {
    return CompletableFuture.completedFuture(1);
  }

  @Override
  public CompletableFuture<Long> offsetAfterTimestampMillis(int partitionIndex, long millis) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'offsetAfterTimestampMillis'");
  }

  @Override
  public CompletableFuture<Long> startOffset(int partitionIndex) {
    return CompletableFuture.completedFuture(start);
  }
}
