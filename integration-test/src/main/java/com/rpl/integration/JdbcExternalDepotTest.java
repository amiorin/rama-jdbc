package com.rpl.integration;

import com.rpl.rama.*;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.Ops;
import com.rpl.rama.test.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;

import java.time.Duration;
import java.util.*;

public class JdbcExternalDepotTest {
  public static class JdbcExternalDepotModule implements RamaModule {
    @Override
    public void define(Setup setup, Topologies topologies) {
        System.out.println("Done!");
    }
  }

  public static void main(String [] args) throws Exception {
      System.out.println("Launching cluster...");
      try(InProcessCluster cluster = InProcessCluster.create()) {
        RamaModule module = new JdbcExternalDepotModule();
        String moduleName = module.getClass().getName();
        System.out.println("Launching module...");
        cluster.launchModule(module, new LaunchConfig(4, 2));
        System.out.println("Done!");
      }
  }
}
