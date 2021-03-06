package com.sonicbase.accept.database;

import com.sonicbase.client.DatabaseClient;
import com.sonicbase.common.Config;
import com.sonicbase.jdbcdriver.ConnectionProxy;
import com.sonicbase.server.DatabaseServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;

public class TestMisc {

  private Connection conn;

  @BeforeClass
  public void beforeClass() throws Exception {
    String configStr = IOUtils.toString(new BufferedInputStream(getClass().getResourceAsStream("/config/config-4-servers.yaml")), "utf-8");
    Config config = new Config(configStr);

    FileUtils.deleteDirectory(new File(System.getProperty("user.home"), "db-data"));

    DatabaseClient.getServers().clear();

    final DatabaseServer[] dbServers = new DatabaseServer[4];

    String role = "primaryMaster";

    List<Future> futures = new ArrayList<>();
    for (int i = 0; i < dbServers.length; i++) {
      //      futures.add(executor.submit(new Callable() {
//        @Override
//        public Object call() throws Exception {
//          String role = "primaryMaster";

      dbServers[i] = new DatabaseServer();
      dbServers[i].setConfig(config, "4-servers", "localhost", 9010 + (50 * i), true, new AtomicBoolean(true), new AtomicBoolean(true),null, false);
      dbServers[i].setRole(role);
//          return null;
//        }
//      }));
    }
    for (Future future : futures) {
      future.get();
    }

    for (DatabaseServer server : dbServers) {
      server.shutdownRepartitioner();
    }

    //DatabaseClient client = new DatabaseClient("localhost", 9010, true);

    Class.forName("com.sonicbase.jdbcdriver.Driver");

    conn = DriverManager.getConnection("jdbc:sonicbase:localhost:9010", "user", "password");

    ((ConnectionProxy) conn).getDatabaseClient().createDatabase("test");

    conn.close();

    conn = DriverManager.getConnection("jdbc:sonicbase:localhost:9010/test", "user", "password");

    PreparedStatement stmt = conn.prepareStatement("create table Persons (id BIGINT, id2 BIGINT, socialSecurityNumber VARCHAR(20), relatives VARCHAR(64000), restricted BOOLEAN, gender VARCHAR(8), PRIMARY KEY (id))");
    stmt.executeUpdate();

    stmt = conn.prepareStatement("create table Memberships (id1 BIGINT, id2 BIGINT, resortId BIGINT, PRIMARY KEY (id1, id2))");
    stmt.executeUpdate();

    stmt = conn.prepareStatement("create table Resorts (resortId BIGINT, resortName VARCHAR(20), PRIMARY KEY (resortId))");
    stmt.executeUpdate();

    stmt = conn.prepareStatement("create table nokey (id BIGINT, id2 BIGINT)");
    stmt.executeUpdate();

    stmt = conn.prepareStatement("create index socialSecurityNumber on persons(socialSecurityNumber)");
     stmt.executeUpdate();


  }

  public static double getMemValue(String memStr) {
    int qualifierPos = memStr.toLowerCase().indexOf("m");
    if (qualifierPos == -1) {
      qualifierPos = memStr.toLowerCase().indexOf("g");
      if (qualifierPos == -1) {
        qualifierPos = memStr.toLowerCase().indexOf("t");
        if (qualifierPos == -1) {
          qualifierPos = memStr.toLowerCase().indexOf("k");
          if (qualifierPos == -1) {
            qualifierPos = memStr.toLowerCase().indexOf("b");
          }
        }
      }
    }
    double value = 0;
    if (qualifierPos == -1) {
      value = Double.valueOf(memStr.trim());
      value = value / 1024d / 1024d / 1024d;
    }
    else {
      char qualifier = memStr.toLowerCase().charAt(qualifierPos);
      value = Double.valueOf(memStr.substring(0, qualifierPos).trim());
      if (qualifier == 't') {
        value = value * 1024d;
      }
      else if (qualifier == 'm') {
        value = value / 1024d;
      }
      else if (qualifier == 'k') {
        value = value / 1024d / 1024d;
      }
    }
    return value;
  }

  @Test
  public void testStoredProcedure() throws SQLException {
    PreparedStatement stmt = conn.prepareStatement("CREATE PROCEDURE RAISE_PRICE(\n" +
        "    IN coffeeName varchar(32),\n" +
        "    IN maximumPercentage float,\n" +
        "    INOUT newPrice float)\n" +
        "    PARAMETER STYLE JAVA\n" +
        "    LANGUAGE JAVA\n" +
        "    DYNAMIC RESULT SETS 0\n" +
        "    EXTERNAL NAME 'com.oracle.tutorial.jdbc.\n" +
        "        StoredProcedureJavaDBSample.raisePrice'");
    stmt.execute();
  }

  @Test
  public void testCallStoredProcedure() throws SQLException {
    PreparedStatement stmt = conn.prepareStatement("CALL myProdcedure(1, 2, 3)");
    stmt.execute();
  }

  @Test
  public void testMem() {
    String secondToLastLine = "  PID USER      PR  NI    VIRT    RES    SHR S  %CPU %MEM     TIME+ COMMAND";
    String lastLine = "20631 ubuntu    20   0 38.144g 4.746g  22044 S 256.2  7.9   2:07.40 java";
    secondToLastLine = secondToLastLine.trim();
    lastLine = lastLine.trim();
    String[] headerParts = secondToLastLine.split("\\s+");
    String[] parts = lastLine.split("\\s+");
    for (int i = 0; i < headerParts.length; i++) {
      if (headerParts[i].toLowerCase().trim().equals("res")) {
        String memStr = parts[i];
        System.out.println("res=" + getMemValue(memStr));
      }
    }

  }

  @Test
  public void testWhite() {
    String str = "s   a b  c ";
    String[] parts = str.split("\\s+");
    System.out.println("test");
  }

  @Test
  public void testMath() {
    double value = 17179869184d;
    value = value / 1024d / 1024d / 1024d;
    System.out.println(value);
  }

  @Test
  public void testIndex() {
    ConcurrentSkipListMap<Object[], Integer> index = new ConcurrentSkipListMap<>(new Comparator<Object[]>() {
      @Override
      public int compare(Object[] o1, Object[] o2) {
        for (int i = 0; i < o1.length; i++) {
          if (o1[i] == null) {
            continue;
          }
          if (o2[i] == null) {
            continue;
          }
          if ((int) o1[i] > (int) o2[i]) {
            return 1;
          }
          if ((int) o1[i] < (int) o2[i]) {
            return -1;
          }
        }
        return 0;
      }
    });

    index.put(new Object[]{1, 100}, 1);
    index.put(new Object[]{1, 101}, 1);
    index.put(new Object[]{1, 102}, 1);
    index.put(new Object[]{1, 103}, 1);
    index.put(new Object[]{1, 104}, 1);
    index.put(new Object[]{1, 105}, 1);

    index.put(new Object[]{2, 100}, 1);
    index.put(new Object[]{2, 101}, 1);
    index.put(new Object[]{2, 102}, 1);
    index.put(new Object[]{2, 103}, 1);
    index.put(new Object[]{2, 104}, 1);
    index.put(new Object[]{2, 105}, 1);

    index.put(new Object[]{3, 100}, 1);
    index.put(new Object[]{3, 101}, 1);
    index.put(new Object[]{3, 102}, 1);
    index.put(new Object[]{3, 103}, 1);
    index.put(new Object[]{3, 104}, 1);
    index.put(new Object[]{3, 105}, 1);

    Object[] key = index.firstKey();
    key = index.higherKey(new Object[]{1, null});
    assertEquals((int) key[0], 2);
    key = index.higherKey(new Object[]{2, null});
    assertEquals((int) key[0], 3);
    key = index.higherKey(new Object[]{3, 100});
    assertEquals((int) key[0], 3);
    assertEquals((int) key[1], 101);
  }


}
