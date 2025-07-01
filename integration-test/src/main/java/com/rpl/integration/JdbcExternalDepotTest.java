package com.rpl.integration;

import com.rpl.rama.*;
import com.rpl.rama.jdbc.JdbcExternalDepot2;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.Ops;
import com.rpl.rama.test.*;

public class JdbcExternalDepotTest {
  public static class JdbcExternalDepotModule implements RamaModule {
    private static final String DB_PORT = System.getenv("PGPORT");
    private static final String DB_URL = "jdbc:postgresql://localhost:" + DB_PORT + "/rama_jdbc_db";
    private static final String DB_USER = "rama_jdbc_user";
    private static final String DB_PASSWORD = "";
    private static final String DB_TABLE = "users_cdc";
    private static final int DEFAULT_FETCH_SIZE = 1000;

    @Override
    public void define(Setup setup, Topologies topologies) {

      setup.declareObject("*jdbc", new JdbcExternalDepot2(DB_URL,
                                                          DB_USER,
                                                          DB_PASSWORD,
                                                          DB_TABLE,
                                                          DEFAULT_FETCH_SIZE));

      StreamTopology s = topologies.stream("s");
      s.source("*jdbc", StreamSourceOptions.startFromBeginning()).out("*auditRecord")
       .each(Ops.PRINTLN, "*auditRecord");
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
