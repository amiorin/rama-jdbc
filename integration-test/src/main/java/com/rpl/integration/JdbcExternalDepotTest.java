package com.rpl.integration;

import com.rpl.rama.*;
import com.rpl.rama.jdbc.JdbcExternalDepot;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.Ops;
import com.rpl.rama.test.*;

public class JdbcExternalDepotTest {
  public static class JdbcExternalDepotModule implements RamaModule {
    @Override
    public void define(Setup setup, Topologies topologies) {
      setup.declareObject("*jdbc", new JdbcExternalDepot());

      StreamTopology s = topologies.stream("s");
      s.source("*jdbc", StreamSourceOptions.startFromBeginning()).out("*long")
       .each(Ops.PRINTLN, "*long");
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
        Thread.sleep(10 * 1000);
        System.out.println("Done!");
      }
  }
}
