package com.sonicbase.accept.bench;

import com.sonicbase.client.DatabaseClient;
import com.sonicbase.common.Config;
import com.sonicbase.jdbcdriver.ParameterHandler;
import com.sonicbase.server.DatabaseServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;

public class TestRebalance {

  @Test(enabled=false)
  public void testBasics() throws Exception {
    String configStr = IOUtils.toString(new BufferedInputStream(getClass().getResourceAsStream("/config/config-4-servers.yaml")), "utf-8");
    Config config = new Config(configStr);

    FileUtils.deleteDirectory(new File(System.getProperty("user.home"), "db-data"));

    final DatabaseServer[] dbServers = new DatabaseServer[8];

    ThreadPoolExecutor executor = new ThreadPoolExecutor(32, 32, 10000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
    List<Future> futures = new ArrayList<>();
    for (int i = 0; i < dbServers.length; i++) {
      final int shard = i;
      futures.add(executor.submit((Callable) () -> {
        String role = "primaryMaster";
        dbServers[shard] = new DatabaseServer();
        dbServers[shard].setConfig(config, "test", "localhost", 9010 + (50 * shard), true, new AtomicBoolean(true), new AtomicBoolean(true),null, false);
        dbServers[shard].setRole(role);
        return null;
      }));
    }
    for (Future future : futures) {
      future.get();
    }
    DatabaseClient client = new DatabaseClient("localhost", 9010, -1, -1, true);

    ParameterHandler parms = new ParameterHandler();

    client.executeQuery("test",
        "create table Persons (id BIGINT, id2 BIGINT, socialSecurityNumber VARCHAR(20), relatives VARCHAR(64000), restricted BOOLEAN, gender VARCHAR(8), PRIMARY KEY (id))",
        parms, false, null, true);

    client.executeQuery("test",
        "create index socialSecurityNumber on persons(socialSecurityNumber)", parms, false, null, true);

    client.syncSchema();

    List<Long> ids = new ArrayList<>();

    Class.forName("com.sonicbase.jdbcdriver.Driver");

    Connection conn = DriverManager.getConnection("jdbc:sonicbase:localhost:9010", "user", "password");

    //test upsert
    int recordCount = dbServers.length * 50;

    for (int i = 0; i < recordCount; i++) {
      PreparedStatement stmt = conn.prepareStatement("insert into persons (id, socialSecurityNumber, relatives, restricted, gender) VALUES (?, ?, ?, ?, ?)");
      stmt.setLong(1, i * 1000);
      stmt.setString(2, "933-28-" + i);
      stmt.setString(3, "12345678901,12345678901|12345678901,12345678901,12345678901,12345678901|12345678901");
      stmt.setBoolean(4, false);
      stmt.setString(5, "m");
      assertEquals(stmt.executeUpdate(), 1);
      ids.add((long) i);
    }

    assertEquals(client.getPartitionSize("test", 0, "persons", "_primarykey"), 400);
    assertEquals(client.getPartitionSize("test", 1, "persons", "_primarykey"), 0);
    assertEquals(client.getPartitionSize("test", 2, "persons", "_primarykey"), 0);
    assertEquals(client.getPartitionSize("test", 3, "persons", "_primarykey"), 0);

    //client.beginRebalance("persons", "_primarykey");
    client.beginRebalance("test");

    while (true) {
      if (client.isRepartitioningComplete("test")) {
        break;
      }
      Thread.sleep(1000);
    }

    assertEquals(client.getPartitionSize("test", 0, "persons", "_primarykey"), 101);
    assertEquals(client.getPartitionSize("test", 1, "persons", "_primarykey"), 100);
    assertEquals(client.getPartitionSize("test", 2, "persons", "_primarykey"), 100);
    assertEquals(client.getPartitionSize("test", 3, "persons", "_primarykey"), 99);

    //sync schema
    PreparedStatement stmt = conn.prepareStatement("create table Persons2 (id BIGINT, id2 BIGINT, socialSecurityNumber VARCHAR(20), relatives VARCHAR(64000), restricted BOOLEAN, gender VARCHAR(8), PRIMARY KEY (id))");
    stmt.executeUpdate();

    for (int i = 0; i < recordCount; i++) {
      stmt = conn.prepareStatement("insert into persons (id, socialSecurityNumber, relatives, restricted, gender) VALUES (" + 399 * 1000 + i + ", ?, ?, ?, ?)");
      //stmt.setLong(1, 399 * 1000 + i);
      stmt.setString(1, "933-28-" + i);
      stmt.setString(2, "12345678901,12345678901|12345678901,12345678901,12345678901,12345678901|12345678901");
      stmt.setBoolean(3, false);
      stmt.setString(4, "m");
      assertEquals(stmt.executeUpdate(), 1);
      ids.add((long) i);
    }


    assertEquals(client.getPartitionSize("test", 0, "persons", "_primarykey"), 101);
    assertEquals(client.getPartitionSize("test", 1, "persons", "_primarykey"), 100);
    assertEquals(client.getPartitionSize("test", 2, "persons", "_primarykey"), 100);
    assertEquals(client.getPartitionSize("test", 3, "persons", "_primarykey"), 499);


    //client.beginRebalance("persons", "_primarykey");
    client.beginRebalance("test");

    while (true) {
      if (client.isRepartitioningComplete("test")) {
        break;
      }
      Thread.sleep(1000);
    }

    assertEquals(client.getPartitionSize("test", 0, "persons", "_primarykey"), 201);
    assertEquals(client.getPartitionSize("test", 1, "persons", "_primarykey"), 200);
    assertEquals(client.getPartitionSize("test", 2, "persons", "_primarykey"), 200);
    assertEquals(client.getPartitionSize("test", 3, "persons", "_primarykey"), 199);


    executor.shutdownNow();
  }

}
