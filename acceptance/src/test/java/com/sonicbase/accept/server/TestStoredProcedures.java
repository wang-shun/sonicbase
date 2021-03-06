package com.sonicbase.accept.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonicbase.client.DatabaseClient;
import com.sonicbase.common.Config;
import com.sonicbase.jdbcdriver.ConnectionProxy;
import com.sonicbase.server.DatabaseServer;
import com.sonicbase.server.NettyServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestStoredProcedures {

  private Connection conn;
  final List<Long> ids = new ArrayList<>();
  com.sonicbase.server.DatabaseServer[] dbServers;
  private NettyServer serverA1;
  private NettyServer serverA2;

  @AfterClass(alwaysRun = true)
  public void afterClass() throws SQLException {
    conn.close();
    for (com.sonicbase.server.DatabaseServer server : dbServers) {
      server.shutdown();
    }
    serverA1.shutdown();
    serverA2.shutdown();

    System.out.println("client refCount=" + DatabaseClient.clientRefCount.get() + ", sharedClients=" + DatabaseClient.sharedClients.size());
    for (DatabaseClient client : DatabaseClient.allClients) {
      System.out.println("Stack:\n" + client.getAllocatedStack());
    }

    System.out.println("shutdown complete");
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    System.setProperty("log4j.configuration", "test-log4j.xml");

    String configStr = IOUtils.toString(new BufferedInputStream(getClass().getResourceAsStream("/config/config-2-servers-a.yaml")), "utf-8");
    Config config = new Config(configStr);

    FileUtils.deleteDirectory(new File(System.getProperty("user.home"), "db-data"));

    DatabaseClient.getServers().clear();

    dbServers = new com.sonicbase.server.DatabaseServer[2];
    ThreadPoolExecutor executor = new ThreadPoolExecutor(32, 32, 10000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1000), new ThreadPoolExecutor.CallerRunsPolicy());

    String role = "primaryMaster";


    final CountDownLatch latch = new CountDownLatch(4);
    serverA1 = new NettyServer(128);
    Thread thread = new Thread(() -> {
      serverA1.startServer(new String[]{"-port", String.valueOf(9010), "-host", "localhost",
          "-mport", String.valueOf(9010), "-mhost", "localhost", "-cluster", "2-servers-a", "-shard", String.valueOf(0)});
      latch.countDown();
    });
    thread.start();
    while (true) {
      if (serverA1.isRunning()) {
        break;
      }
      Thread.sleep(100);
    }

    serverA2 = new NettyServer(128);
    thread = new Thread(() -> {
      serverA2.startServer(new String[]{"-port", String.valueOf(9060), "-host", "localhost",
          "-mport", String.valueOf(9060), "-mhost", "localhost", "-cluster", "2-servers-a", "-shard", String.valueOf(1)});
      latch.countDown();
    });
    thread.start();

    while (true) {
      if (serverA2.isRunning()) {
        break;
      }
      Thread.sleep(100);
    }

    while (true) {
      if (serverA1.isRecovered() && serverA2.isRecovered()) {
        break;
      }
      Thread.sleep(100);
    }
    dbServers[0] = serverA1.getDatabaseServer();
    dbServers[1] = serverA2.getDatabaseServer();


    for (com.sonicbase.server.DatabaseServer server : dbServers) {
      server.shutdownRepartitioner();
    }

    Class.forName("com.sonicbase.jdbcdriver.Driver");

    conn = DriverManager.getConnection("jdbc:sonicbase:localhost:9010", "user", "password");

    ((ConnectionProxy)conn).getDatabaseClient().createDatabase("db");

    conn.close();

    conn = DriverManager.getConnection("jdbc:sonicbase:localhost:9010/db", "user", "password");


    DatabaseClient client = ((ConnectionProxy)conn).getDatabaseClient();


    PreparedStatement stmt = conn.prepareStatement("create table Persons (id1 BIGINT, id2 BIGINT, id3 BIGINT, id4 BIGINT, id5 BIGINT, num DOUBLE, socialSecurityNumber VARCHAR(20), relatives VARCHAR(64000), restricted BOOLEAN, gender VARCHAR(8), timestamp TIMESTAMP, PRIMARY KEY (id1))");
    stmt.executeUpdate();

    //test upsert


    for (int i = 0; i < 10; i++) {
      stmt = conn.prepareStatement("insert into persons (id1, id2, id3, id4, id5, num, socialSecurityNumber, relatives, restricted, gender, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
      stmt.setLong(1, i);
      stmt.setLong(2, (i + 100) % 2);
      stmt.setLong(3, -1 * (i + 100));
      stmt.setLong(4, (i + 100) % 3);
      stmt.setDouble(5, 1);
      stmt.setDouble(6, i * 0.5);
      stmt.setString(7, "s" + i);
      stmt.setString(8, "12345678901,12345678901|12345678901,12345678901,12345678901,12345678901|12345678901");
      stmt.setBoolean(9, false);
      stmt.setString(10, "m");
      Timestamp timestamp = new Timestamp(2000 + i - 1900, i % 12 - 1, i % 30, i % 24, i % 60, i % 60, 0);
      stmt.setTimestamp(11, timestamp);
      assertEquals(stmt.executeUpdate(), 1);
      ids.add((long) i);
    }

    for (com.sonicbase.server.DatabaseServer server : dbServers) {
      server.shutdownRepartitioner();
    }

//      long size = client.getPartitionSize("test", 0, "children", "_1_socialsecuritynumber");
//      assertEquals(size, 10);

    client.beginRebalance("db");


    while (true) {
      if (client.isRepartitioningComplete("db")) {
        break;
      }
      Thread.sleep(1000);
    }

    for (com.sonicbase.server.DatabaseServer server : dbServers) {
      server.shutdownRepartitioner();
    }
    client.syncSchema();

    client.beginRebalance("db");


    while (true) {
      if (client.isRepartitioningComplete("db")) {
        break;
      }
      Thread.sleep(1000);
    }

    for (DatabaseServer server : dbServers) {
      server.shutdownRepartitioner();
    }

    client.syncSchema();
    executor.shutdownNow();
  }


  @Test
  public void test() {

  }

  @Test
  public void test1() throws SQLException {
    String query = "call procedure('com.sonicbase.procedure.MyStoredProcedure1')";
    PreparedStatement procedureStmt = conn.prepareStatement(query);
    ResultSet rs = procedureStmt.executeQuery();
    int offset = 3;
    while (true) {
      assertTrue(rs.next());
      assertEquals(rs.getLong("id1"), (long)offset);
      assertEquals(rs.getString("socialsecuritynumber"), "s" + offset);
      assertEquals(rs.getString("gender"), "m");
      offset++;
      if (offset == 9) {
        break;
      }
    }

    rs.close();
    procedureStmt.close();
  }

  @Test
  public void test2() throws SQLException {
    String tableName = null;
    try {
      String query = "call procedure ('com.sonicbase.procedure.MyStoredProcedure2', 100000 ) ";
      PreparedStatement procedureStmt = conn.prepareStatement(query);
      ResultSet procedureRs = procedureStmt.executeQuery();
      if (procedureRs.next()) {
        tableName = procedureRs.getString("tableName");
      }

      procedureRs.close();
      procedureStmt.close();

      PreparedStatement resultsStmt = conn.prepareStatement("select * from " + tableName);
      ResultSet rsultsRs = resultsStmt.executeQuery();
      int offset = 3;
      while (true) {
        assertTrue(rsultsRs.next());
        assertEquals(rsultsRs.getLong("id1"), (long)offset);
        assertEquals(rsultsRs.getString("socialsecuritynumber"), "s" + offset);
        assertEquals(rsultsRs.getString("gender"), "m");
        offset++;
        if (offset == 9) {
          break;
        }
      }
      rsultsRs.close();
      resultsStmt.close();
    }
    finally {
      try {
        if (tableName != null) {
          PreparedStatement stmt = conn.prepareStatement("drop table " + tableName);
          stmt.executeUpdate();
          stmt.close();
        }
      }
      catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  @Test
  public void test3() throws SQLException {
    String query = "call procedure ('com.sonicbase.procedure.MyStoredProcedure3') ";
    PreparedStatement procedureStmt = conn.prepareStatement(query);
    ResultSet rs = procedureStmt.executeQuery();
    int offset = 3;
    while (true) {
      assertTrue(rs.next());
      assertEquals(rs.getLong("id1"), (long)offset);
      assertEquals(rs.getString("socialsecuritynumber"), "s" + offset);
      assertEquals(rs.getString("gender"), "m");
      offset++;
      if (offset == 9) {
        break;
      }
    }

    rs.close();
    procedureStmt.close();
  }

  @Test
  public void test4() throws SQLException {
    String query = "call procedure ('com.sonicbase.procedure.MyStoredProcedure4') ";
    PreparedStatement procedureStmt = conn.prepareStatement(query);
    ResultSet rs = procedureStmt.executeQuery();
    int offset = 3;
    while (true) {
      assertTrue(rs.next());
      assertEquals(rs.getLong("id1"), (long)offset);
      assertEquals(rs.getString("socialsecuritynumber"), "s" + offset);
      assertEquals(rs.getString("gender"), "m");
      offset++;
      if (offset == 9) {
        break;
      }
    }

    rs.close();
    procedureStmt.close();
  }

}
