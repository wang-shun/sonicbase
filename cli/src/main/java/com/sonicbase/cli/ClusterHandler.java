/* © 2018 by Intellectual Reserve, Inc. All rights reserved. */
package com.sonicbase.cli;

import com.sonicbase.client.ReconfigureResults;
import com.sonicbase.common.ComObject;
import com.sonicbase.common.Config;
import com.sonicbase.jdbcdriver.ConnectionProxy;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.sonicbase.common.MemUtil.getMemValue;

public class ClusterHandler {

  private static final Logger logger = LoggerFactory.getLogger(ClusterHandler.class);
  private static final String INSTALL_DIRECTORY_STR = "installDirectory";
  private static final String JSON_STR = ".json";
  private static final String CONFIG_STR = "/config-";
  private static final String POWERSHELL_STR = "powershell";
  private static final String CREDENTIALS_STR = "credentials/";
  private static final String SHARDS_STR = "shards";
  private static final String UTF_8_STR = "utf-8";
  private static final String REPLICAS_STR = "replicas";
  private static final String USERS_LOWRYDA_SONICBASE_CONFIG_CONFIG_STR = "/Users/lowryda/sonicbase/config/config-";
  private static final String PUBLIC_ADDRESS_STR = "publicAddress";
  private static final String PRIVATE_ADDRESS_STR = "privateAddress";
  private static final String LOCAL_HOST_NUMS_STR = "127.0.0.1";
  private static final String LOCALHOST_STR = "localhost";
  private static final String USER_HOME_STR = "user.home";
  private static final String USER_DIR_STR = "user.dir";
  private static final String PORT_STR = ", port=";
  private static final String ERROR_NOT_USING_A_CLUSTER_STR = "Error, not using a cluster";
  private static final String MAX_JAVA_HEAP_STR = ", maxJavaHeap=";
  private static final String NONE_STR = "__none__";
  private static final String DATABASE_SERVER_HEALTH_CHECK_STR = "DatabaseServer:healthCheck";
  private static final String STATUS_OK_STR = "{\"status\" : \"ok\"}";
  private static final String WAITING_FOR_STR = "Waiting for ";
  private static final String ERROR_TRUE_STR = ", error=true";
  private static final String DATABASE_SERVER_GET_RECOVER_PROGRESS_STR = "DatabaseServer:getRecoverProgress";
  private static final String USER_KNOWN_HOSTS_FILE_DEV_NULL_STR = "UserKnownHostsFile=/dev/null";
  private static final String STRICT_HOST_KEY_CHECKING_NO_STR = "StrictHostKeyChecking=no";
  private static final String LAST_STR = ".last";
  private final Cli cli;

  ClusterHandler(Cli cli) {
    this.cli = cli;
  }

  void deploy() {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }
    cli.println("deploying: cluster=" + cluster);

    Deploy deploy = new Deploy();
    deploy.deploy(cli.getCurrCluster(), "0");

    cli.println("Finished deploy: cluster=" + cluster);
  }

  private void startServer(Config config, String externalAddress, String privateAddress, String port, String installDir,
                                  String cluster, boolean disable, AtomicReference<Double> lastTotalGig) throws IOException, InterruptedException {
    try {
      String deployUser = config.getString("user");
      String maxHeap = config.getString("maxJavaHeap");
      if (port == null) {
        port = "9010";
      }
      String searchHome = installDir;
      if (externalAddress.equals(LOCAL_HOST_NUMS_STR) || externalAddress.equals(LOCALHOST_STR)) {
        maxHeap = cli.getMaxHeap(config);
        if (cli.isWindows()) {
          if (!searchHome.substring(1, 2).equals(":")) {
            File file = new File(System.getProperty(USER_HOME_STR), searchHome);
            searchHome = file.getAbsolutePath();
          }
        }
        else {
          if (!searchHome.startsWith("/")) {
            File file = new File(System.getProperty(USER_HOME_STR), searchHome);
            searchHome = file.getAbsolutePath();
          }
        }
      }
      cli.println("Home=" + searchHome);
      if (externalAddress.equals(LOCAL_HOST_NUMS_STR) || externalAddress.equals(LOCALHOST_STR)) {
        searchHome = new File(System.getProperty(USER_DIR_STR)).getAbsolutePath();
        maxHeap = cli.getMaxHeap(config);
        ProcessBuilder builder = null;
        if (cli.isCygwin() || cli.isWindows()) {
          builder = new ProcessBuilder().command("bin/start-db-server-task.bat", privateAddress, port, maxHeap, searchHome, cluster, disable ? "disable" : "enable");
          Process p = builder.start();
          p.waitFor();
          cli.println("Started server: address=" + externalAddress + PORT_STR + port + MAX_JAVA_HEAP_STR + maxHeap);
        }
        else {
          builder = new ProcessBuilder().command("bash", "bin/start-db-server", privateAddress, port, maxHeap, searchHome, cluster, disable ? "disable" : "enable");
          Process p = builder.start();
          StringBuilder sbuilder = new StringBuilder();
          InputStream in = p.getInputStream();
          while (true) {
            int b = in.read();
            if (b == -1) {
              break;
            }
            sbuilder.append(String.valueOf((char) b));
          }
          cli.println(sbuilder.toString());
          cli.println("Started server - linux: address=" + externalAddress + PORT_STR + port + MAX_JAVA_HEAP_STR + maxHeap + ", searchHome=" + searchHome + ", cluster=" + cluster);
        }
      }
      else {
        cli.println("Current working directory=" + System.getProperty(USER_DIR_STR));
        String maxStr = config.getString("maxJavaHeap");
        if (maxStr != null && maxStr.contains("%")) {
          ProcessBuilder builder = null;
          Process p = null;
          if (cli.isWindows()) {
            File file = new File("bin/remote-get-mem-total.ps1");
            String str = IOUtils.toString(new FileInputStream(file), UTF_8_STR);
            str = str.replaceAll("\\$1", new File(System.getProperty(USER_DIR_STR), CREDENTIALS_STR + cluster + "-" + cli.getUsername()).getAbsolutePath().replaceAll("\\\\", "/"));
            str = str.replaceAll("\\$2", cli.getUsername());
            str = str.replaceAll("\\$3", externalAddress);
            str = str.replaceAll("\\$4", installDir);
            File outFile = new File(System.getProperty(USER_DIR_STR), "/tmp/" + externalAddress + "-" + port + "-remote-get-mem-total.ps1");
                   outFile.getParentFile().mkdirs();
            FileUtils.deleteQuietly(outFile);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {
              writer.write(str);
            }

            builder = new ProcessBuilder().command(POWERSHELL_STR, "-F", outFile.getAbsolutePath());
            p = builder.start();
          }
          else {
            builder = new ProcessBuilder().command("bash", "bin/remote-get-mem-total", deployUser + "@" + externalAddress, installDir);
            p = builder.start();
          }
          BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
          String line = reader.readLine();
          double totalGig = 0;
          if (cli.isWindows()) {
            totalGig = Double.valueOf(line) / 1000d / 1000d / 1000d;
          }
          else {
            try {
              if (line.toLowerCase().startsWith("memtotal")) {
                line = line.substring("MemTotal:".length()).trim();
                totalGig = getMemValue(line);
              }
              else {
                String[] parts = line.split(" ");
                String memStr = parts[1];
                totalGig = getMemValue(memStr);
              }
              lastTotalGig.set(totalGig);
            }
            catch (Exception e) {
              logger.error("Error getting totalGib", e);
              totalGig = lastTotalGig.get();
            }
          }
          p.waitFor();
          maxStr = maxStr.substring(0, maxStr.indexOf('%'));
          double maxPercent = Double.parseDouble(maxStr);
          double maxGig = totalGig * (maxPercent / 100);
          maxHeap = (int) Math.floor(maxGig * 1024d) + "m";
        }

        cli.println("Started server: address=" + externalAddress + PORT_STR + port + MAX_JAVA_HEAP_STR + maxHeap);
        if (cli.isWindows()) {
          File file = new File("bin/remote-start-db-server.ps1");
          String str = IOUtils.toString(new FileInputStream(file), UTF_8_STR);
          str = str.replaceAll("\\$1", new File(System.getProperty(USER_DIR_STR), CREDENTIALS_STR + cluster + "-" + cli.getUsername()).getAbsolutePath().replaceAll("\\\\", "/"));
          str = str.replaceAll("\\$2", cli.getUsername());
          str = str.replaceAll("\\$3", externalAddress);
          str = str.replaceAll("\\$4", installDir);
          str = str.replaceAll("\\$5", privateAddress);
          str = str.replaceAll("\\$6", port);
          str = str.replaceAll("\\$7", maxHeap);
          str = str.replaceAll("\\$8", cluster);
          str = str.replaceAll("\\$9", disable ? "disable" : "enable");
          File outFile = new File("tmp/" + externalAddress + "-" + port + "-remote-start-db-server.ps1");
          outFile.getParentFile().mkdirs();
          FileUtils.deleteQuietly(outFile);
          try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {
            writer.write(str);
          }

          ProcessBuilder builder = new ProcessBuilder().command(POWERSHELL_STR, "-F", outFile.getAbsolutePath());
          builder.start();
        }
        else {
          ProcessBuilder builder = new ProcessBuilder().command("bash", "bin/do-start", deployUser + "@" + externalAddress,
              installDir, privateAddress, port, maxHeap, searchHome, cluster, disable ? "disable" : "enable");
          Process p = builder.start();
          p.waitFor();
        }
      }
    }
    catch (Exception e) {
      cli.println("Error starting server: publicAddress=" + externalAddress + ", internalAddress=" + privateAddress);
      throw e;
    }
  }

  void startCluster() throws IOException, InterruptedException, SQLException, ClassNotFoundException, ExecutionException {
    final String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    Config config = cli.getConfig(cluster);
    final String installDir = cli.resolvePath(config.getString(INSTALL_DIRECTORY_STR));
    List<Config.Shard> shards = config.getShards();

    stopCluster();

    Thread.sleep(2000);

    final List<Config.Replica> masterReplica = shards.get(0).getReplicas();
    Config.Replica master = masterReplica.get(0);
    startServer(config, master.getString(PUBLIC_ADDRESS_STR), master.getString(PRIVATE_ADDRESS_STR),
        String.valueOf(master.getInt("port")), installDir, cluster, false, new AtomicReference<Double>(0d));
    Thread.sleep(5_000);
    cli.initConnection();

    ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 10_000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
    try {
      final AtomicReference<Double> lastTotalGig = new AtomicReference<>(0d);
      List<Future> futures = new ArrayList<>();
      for (int i = 0; i < shards.size(); i++) {
        final int shardOffset = i;
        final List<Config.Replica> replicas = shards.get(i).getReplicas();
        for (int j = 0; j < replicas.size(); j++) {
          final int replicaOffset = j;
          if (shardOffset == 0 && replicaOffset == 0) {
            continue;
          }
          futures.add(executor.submit((Callable) () -> {
            Config.Replica replica = replicas.get(replicaOffset);
            startServer(config, replica.getString(PUBLIC_ADDRESS_STR),
                replica.getString(PRIVATE_ADDRESS_STR), String.valueOf(replica.getInt("port")), installDir, cluster, false,
                lastTotalGig);
            return null;
          }));
        }
      }
      for (Future future : futures) {
        future.get();
      }
    }
    finally {
      executor.shutdownNow();
    }
    final AtomicBoolean printedFinished = new AtomicBoolean();
    for (int i = 0; i < shards.size(); i++) {
      final int shardOffset = i;
      List<Config.Replica> replicas = shards.get(i).getReplicas();
      for (int j = 0; j < replicas.size(); j++) {
        final int replicaOffset = j;
        final Config.Replica replica = replicas.get(j);
        final AtomicBoolean ok2 = new AtomicBoolean();
        while (!ok2.get()) {
          final ComObject cobj = new ComObject(3);
          cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
          cobj.put(ComObject.Tag.SCHEMA_VERSION, 1);
          cobj.put(ComObject.Tag.METHOD, DATABASE_SERVER_HEALTH_CHECK_STR);

          Thread thread = new Thread(() -> {
            try {
              byte[] bytes = cli.getConn().send(null, shardOffset, replicaOffset, cobj, ConnectionProxy.Replica.SPECIFIED, true);
              ComObject retObj = new ComObject(bytes);
              String retStr = retObj.getString(ComObject.Tag.STATUS);
              if (retStr.equals(STATUS_OK_STR)) {
                ok2.set(true);
              }
              else {
                cli.println("Server not healthy: shard=" + shardOffset + ", replica=" + replicaOffset +
                    ", privateAddress=" + replica.getString(PRIVATE_ADDRESS_STR));
              }
            }
            catch (Exception e) {
              ComObject cobj1 = new ComObject(3);
              cobj1.put(ComObject.Tag.DB_NAME, NONE_STR);
              cobj1.put(ComObject.Tag.SCHEMA_VERSION, 1);
              cobj1.put(ComObject.Tag.METHOD, DATABASE_SERVER_GET_RECOVER_PROGRESS_STR);

              try {
                byte[] bytes = cli.getConn().send(null, shardOffset, replicaOffset, cobj1, ConnectionProxy.Replica.SPECIFIED, true);
                ComObject retObj = new ComObject(bytes);
                double percentComplete = retObj.getDouble(ComObject.Tag.PERCENT_COMPLETE);
                String stage = retObj.getString(ComObject.Tag.STAGE);
                Boolean error = retObj.getBoolean(ComObject.Tag.ERROR);

                percentComplete *= 100d;
                cli.println(WAITING_FOR_STR + replica.getString(PRIVATE_ADDRESS_STR) + " to start: stage=" +
                    stage + ", percentComplete=" + String.format("%.2f", percentComplete) + (error != null && error ? ERROR_TRUE_STR : ""));
                try {
                  Thread.sleep(2000);
                }
                catch (InterruptedException e1) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
              catch (Exception e1) {
                if (!printedFinished.get()) {
                  cli.println(WAITING_FOR_STR + replica.getString(PRIVATE_ADDRESS_STR) + " to start: percentComplete=?");
                }
                try {
                  Thread.sleep(2000);
                }
                catch (InterruptedException e2) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            }
          });
          thread.start();
          thread.join(5000);
          thread.interrupt();
        }
      }
    }
    ComObject cobj = new ComObject(1);
    cli.getConn().sendToMaster("MonitorManager:initMonitoringTables", cobj);
    cli.getConn().sendToMaster("OSStatsManager:initMonitoringTables", cobj);

    for (int shard = 0; shard < cli.getConn().getShardCount(); shard++) {
      for (int replica = 0; replica < cli.getConn().getReplicaCount(); replica++) {
        cli.getConn().send("MonitorManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
        cli.getConn().send("OSStatsManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
        cli.getConn().send("StreamManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
      }
    }

    printedFinished.set(true);
    cli.println("Finished starting servers");
  }

  private void startReplica(final int replica, final Config config, final List<Config.Shard> shards,
                            final String installDir, final String cluster) throws InterruptedException, ExecutionException {
    final AtomicReference<Double> lastTotalGig = new AtomicReference<>(0d);
    List<Future> futures = new ArrayList<>();
    for (int i = 0; i < shards.size(); i++) {
      final List<Config.Replica> replicas = shards.get(i).getReplicas();
      for (int j = 0; j < replicas.size(); j++) {
        final int replicaOffset = j;
        if (replicaOffset != replica) {
          continue;
        }
        futures.add(cli.getExecutor().submit((Callable) () -> {
          Config.Replica replica1 = replicas.get(replicaOffset);
          startServer(config, replica1.getString(PUBLIC_ADDRESS_STR),
              replica1.getString(PRIVATE_ADDRESS_STR),
              String.valueOf(replica1.getInt("port")), installDir, cluster, false, lastTotalGig);
          return null;
        }));
      }
    }
    for (Future future : futures) {
      future.get();
    }

    for (int i = 0; i < shards.size(); i++) {
      final int shardOffset = i;
      List<Config.Replica> replicas = shards.get(i).getReplicas();
      for (int j = 0; j < replicas.size(); j++) {
        final int replicaOffset = j;
        if (replicaOffset != replica) {
          continue;
        }
        final Config.Replica replicaDict = replicas.get(j);
        final AtomicBoolean ok2 = new AtomicBoolean();
        while (!ok2.get()) {
          final ComObject cobj = new ComObject(3);
          cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
          cobj.put(ComObject.Tag.SCHEMA_VERSION, 1);
          cobj.put(ComObject.Tag.METHOD, DATABASE_SERVER_HEALTH_CHECK_STR);

          Thread thread = new Thread(() -> {
            try {
              byte[] bytes = cli.getConn().send(null, shardOffset, replicaOffset, cobj, ConnectionProxy.Replica.SPECIFIED);
              ComObject retObj = new ComObject(bytes);
              String retStr = retObj.getString(ComObject.Tag.STATUS);
              if (retStr.equals(STATUS_OK_STR)) {
                ok2.set(true);
              }
              else {
                cli.println("Server not healthy: shard=" + shardOffset + ", replica=" + replicaOffset +
                    ", privateAddress=" + replicaDict.getString(PRIVATE_ADDRESS_STR));
              }
            }
            catch (Exception e) {
              ComObject pcobj = new ComObject(3);
              pcobj.put(ComObject.Tag.DB_NAME, NONE_STR);
              pcobj.put(ComObject.Tag.SCHEMA_VERSION, cli.getConn().getSchemaVersion());
              pcobj.put(ComObject.Tag.METHOD, DATABASE_SERVER_GET_RECOVER_PROGRESS_STR);
              try {
                byte[] bytes = cli.getConn().send(null, shardOffset, replicaOffset,
                    pcobj, ConnectionProxy.Replica.SPECIFIED, true);
                ComObject retObj = new ComObject(bytes);
                double percentComplete = retObj.getDouble(ComObject.Tag.PERCENT_COMPLETE);
                String stage = retObj.getString(ComObject.Tag.STAGE);
                Boolean error = retObj.getBoolean(ComObject.Tag.ERROR);

                percentComplete *= 100d;
                cli.println("Waiting for servers to start... server=" + replicaDict.getString(PRIVATE_ADDRESS_STR) +
                    ", stage=" + stage + ", percentComplete=" + String.format("%.2f", percentComplete) + (error != null && error ? ERROR_TRUE_STR : ""));
                try {
                  Thread.sleep(2000);
                }
                catch (InterruptedException e1) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
              catch (Exception e1) {
                cli.println("Waiting for servers to start... server=" + replicaDict.getString(PRIVATE_ADDRESS_STR) + ", percentComplete=?");
                try {
                  Thread.sleep(2000);
                }
                catch (InterruptedException e2) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            }
          });
          thread.start();
          thread.join(5000);
          thread.interrupt();
        }
      }
    }
    ComObject cobj = new ComObject(1);
    for (int shard = 0; shard < cli.getConn().getShardCount(); shard++) {
      cli.getConn().send("MonitorManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
      cli.getConn().send("OSStatsManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
      cli.getConn().send("StreamManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
    }

  }

  private void reloadServerStatus(String command) throws SQLException, ClassNotFoundException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    cli.initConnection();

    command = command.trim();
    String[] parts = command.split("\\s+");
    final int shard = Integer.parseInt(parts[3]);
    final int replica = Integer.parseInt(parts[4]);

    if (getReloadStatus(cli.getConn(), shard, replica)) {
      cli.println("complete");
    }
    else {
      cli.println("running");
    }
  }

  private static Boolean getReloadStatus(ConnectionProxy conn, int shard, int replica) {
    ComObject cobj = new ComObject(2);
    cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
    cobj.put(ComObject.Tag.SCHEMA_VERSION, conn.getSchemaVersion());
    byte[] bytes = conn.send("DatabaseServer:isServerReloadFinished", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
    ComObject retObj = new ComObject(bytes);
    return retObj.getBoolean(ComObject.Tag.IS_COMPLETE);
  }

  void reloadServer(String command) throws SQLException, ClassNotFoundException, InterruptedException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    cli.initConnection();

    if (command.startsWith("reload server status")) {
      reloadServerStatus(command);
      return;
    }

    command = command.trim();
    String[] parts = command.split("\\s+");
    final int shard = Integer.parseInt(parts[2]);
    final int replica = Integer.parseInt(parts[3]);

    reloadServer(cli, cluster, cli.getConn(), shard, replica);

    Config config = cli.getConfig(cluster);
    List<Config.Shard> shards = config.getShards();
    final List<Config.Replica> masterReplica = shards.get(shard).getReplicas();
    final Config.Replica currReplica = masterReplica.get(replica);

    monitorServerStartupProgress(cli, shard, replica, currReplica);
  }

  void reloadReplica(String command) throws SQLException, ClassNotFoundException, ExecutionException, InterruptedException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    cli.initConnection();

    if (command.startsWith("reload replica status")) {
      getReplicaReloadStatus(command);
      return;
    }

    command = command.trim();
    String[] parts = command.split("\\s+");
    final int replica = Integer.parseInt(parts[2]);

    ThreadPoolExecutor executor = new ThreadPoolExecutor(cli.getConn().getShardCount(), cli.getConn().getShardCount(), 10_000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
    try {
      List<Future> futures = new ArrayList<>();
      for (int shard = 0; shard < cli.getConn().getShardCount(); shard++) {
        final int finalShard = shard;
        futures.add(executor.submit((Callable) () -> {
          reloadServer(cli, cluster, cli.getConn(), finalShard, replica);
          return null;
        }));
      }
      for (Future future : futures) {
        future.get();
      }
      for (int shard = 0; shard < cli.getConn().getShardCount(); shard++) {
        Config config = cli.getConfig(cluster);
        List<Config.Shard> shards = config.getShards();
        final List<Config.Replica> masterReplica = shards.get(shard).getReplicas();
        final Config.Replica currReplica = masterReplica.get(replica);

        monitorServerStartupProgress(cli, shard, replica, currReplica);
      }
    }
    finally {
      executor.shutdownNow();
    }
  }

  private void getReplicaReloadStatus(String command) throws SQLException, ClassNotFoundException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    cli.initConnection();

    command = command.trim();
    String[] parts = command.split("\\s+");
    final int replica = Integer.parseInt(parts[3]);

    for (int shard = 0; shard < cli.getConn().getShardCount(); shard++) {
      if (!getReloadStatus(cli.getConn(), shard, replica)) {
        cli.println("running");
        return;
      }
    }
    cli.println("complete");
  }


  private static void reloadServer(Cli cli, String cluster, ConnectionProxy conn, int shard, int replica) throws InterruptedException {
    ComObject cobj = new ComObject(2);
    cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
    cobj.put(ComObject.Tag.SCHEMA_VERSION, conn.getSchemaVersion());
    conn.send("DatabaseServer:reloadServer", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
  }


  void rollingRestart() throws IOException, InterruptedException, SQLException, ClassNotFoundException, ExecutionException {
    final String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    Config config = cli.getConfig(cluster);
    final String installDir = cli.resolvePath(config.getString(INSTALL_DIRECTORY_STR));
    List<Config.Shard> shards = config.getShards();

    if (shards.get(0).getReplicas().size() > 1) {
      rollingRestart(config, shards, installDir, cluster);
    }
    else {
      cli.println("Cannot restart a cluster with one replica. Call 'start cluster'.");
    }
  }

  void stopServer(String command) throws InterruptedException, IOException {
    final String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    command = command.trim();
    String[] parts = command.split("\\s+");
    final int shard = Integer.parseInt(parts[2]);
    final int replica = Integer.parseInt(parts[3]);

    Config config = cli.getConfig(cluster);
    final String installDir = cli.resolvePath(config.getString(INSTALL_DIRECTORY_STR));
    List<Config.Shard> shards = config.getShards();
    final List<Config.Replica> masterReplica = shards.get(shard).getReplicas();
    Config.Replica currReplica = masterReplica.get(replica);

    stopServer(config, currReplica.getString(PUBLIC_ADDRESS_STR), currReplica.getString(PRIVATE_ADDRESS_STR),
        String.valueOf(currReplica.getInt("port")), installDir);

  }

  private void stopServer(Config config, String externalAddress, String privateAddress, String port, String installDir) throws IOException, InterruptedException {
    String deployUser = config.getString("user");
    if (externalAddress.equals(LOCAL_HOST_NUMS_STR) || externalAddress.equals(LOCALHOST_STR)) {
      ProcessBuilder builder = null;
      if (cli.isCygwin() || cli.isWindows()) {
        builder = new ProcessBuilder().command("bin/kill-server.bat", port);
      }
      else {
        builder = new ProcessBuilder().command("bash", "bin/kill-server", "NettyServer", "-host", privateAddress, "-port", port);
      }
      Process p = builder.start();
      p.waitFor();
    }
    else {
      ProcessBuilder builder = null;
      Process p = null;
      if (cli.isWindows()) {
        File file = new File("bin/remote-kill-server.ps1");
        String str = IOUtils.toString(new FileInputStream(file), UTF_8_STR);
        str = str.replaceAll("\\$1", new File(System.getProperty(USER_DIR_STR), CREDENTIALS_STR + cli.getCurrCluster() + "-" + cli.getUsername()).getAbsolutePath().replaceAll("\\\\", "/"));
        str = str.replaceAll("\\$2", cli.getUsername());
        str = str.replaceAll("\\$3", externalAddress);
        str = str.replaceAll("\\$4", installDir);
        str = str.replaceAll("\\$5", port);
        File outFile = new File("tmp/" + externalAddress + "-" + port + "-remote-kill-server.ps1");
        outFile.getParentFile().mkdirs();
        FileUtils.deleteQuietly(outFile);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {
          writer.write(str);
        }

        builder = new ProcessBuilder().command(POWERSHELL_STR, "-F", outFile.getAbsolutePath());
        p = builder.start();
      }
      else {
        builder = new ProcessBuilder().command("ssh", "-n", "-f", "-o",
            USER_KNOWN_HOSTS_FILE_DEV_NULL_STR, "-o", STRICT_HOST_KEY_CHECKING_NO_STR, deployUser + "@" +
                externalAddress, installDir + "/bin/kill-server", "NettyServer", "-host", privateAddress, "-port", port);
        p = builder.start();
      }
      p.waitFor();
    }
  }


  void purgeCluster() throws IOException, InterruptedException, ExecutionException {
    final String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    cli.println("Stopping cluster: cluster=" + cluster);
    stopCluster();
    cli.println("Starting purge: cluster=" + cluster);

    Config config = cli.getConfig(cluster);
    final String dataDir = cli.resolvePath(config.getString("dataDirectory"));
    List<Config.Shard> shards = config.getShards();
    Set<String> addresses = new HashSet<>();
    for (int i = 0; i < shards.size(); i++) {
      final List<Config.Replica> replicas = shards.get(i).getReplicas();
      for (int j = 0; j < replicas.size(); j++) {
        Config.Replica replica = replicas.get(j);
        addresses.add(replica.getString(PUBLIC_ADDRESS_STR));
      }
    }
    List<Future> futures = new ArrayList<>();
    for (final String address : addresses) {
      futures.add(cli.getExecutor().submit((Callable) () -> {
        String deployUser = config.getString("user");
        if (address.equals(LOCAL_HOST_NUMS_STR) || address.equals(LOCALHOST_STR)) {
          File file = new File(dataDir);
          if (!cli.isWindows() && !dataDir.startsWith("/")) {
            file = new File(System.getProperty(USER_HOME_STR), dataDir);
          }
          File lastFile = new File(file.getAbsolutePath() + LAST_STR);
          file.renameTo(lastFile);
          cli.println("Deleting directory: dir=" + file.getAbsolutePath());
          FileUtils.deleteDirectory(lastFile);
        }
        else {
          if (cli.isWindows()) {
            final String installDir = cli.resolvePath(config.getString(INSTALL_DIRECTORY_STR));

            ProcessBuilder builder = null;
            File file = new File("bin/remote-purge-data.ps1");
            String str = IOUtils.toString(new FileInputStream(file), UTF_8_STR);
            str = str.replaceAll("\\$1", new File(System.getProperty(USER_DIR_STR), CREDENTIALS_STR + cluster + "-" + cli.getUsername()).getAbsolutePath().replaceAll("\\\\", "/"));
            str = str.replaceAll("\\$2", cli.getUsername());
            str = str.replaceAll("\\$3", address);
            str = str.replaceAll("\\$4", installDir);
            str = str.replaceAll("\\$5", dataDir);
            File outFile = new File("tmp/" + address + "-remote-purge-data.ps1");
            outFile.getParentFile().mkdirs();
            FileUtils.deleteQuietly(outFile);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile)))) {
              writer.write(str);
            }

            builder = new ProcessBuilder().command(POWERSHELL_STR, "-F", outFile.getAbsolutePath());
            Process p = builder.start();
            p.waitFor();
          }
          else {
            ProcessBuilder builder = new ProcessBuilder().command("ssh", "-n", "-f", "-o",
                USER_KNOWN_HOSTS_FILE_DEV_NULL_STR, "-o", STRICT_HOST_KEY_CHECKING_NO_STR, deployUser + "@" +
                    address, "mv", dataDir, dataDir + LAST_STR);
            Process p = builder.start();
            p.waitFor();

            builder = new ProcessBuilder().command("ssh", "-n", "-f", "-o",
                USER_KNOWN_HOSTS_FILE_DEV_NULL_STR, "-o", STRICT_HOST_KEY_CHECKING_NO_STR, deployUser + "@" +
                    address, "rm", "-rf", dataDir + LAST_STR);
            cli.println("purging: address=" + address + ", dir=" + dataDir);
            p = builder.start();
            p.waitFor();

            //delete it twice to make sure
            builder = new ProcessBuilder().command("ssh", "-n", "-f", "-o",
                USER_KNOWN_HOSTS_FILE_DEV_NULL_STR, "-o", STRICT_HOST_KEY_CHECKING_NO_STR, deployUser + "@" +
                    address, "rm", "-rf", dataDir + LAST_STR);
            cli.println("purging: address=" + address + ", dir=" + dataDir);
            p = builder.start();
            p.waitFor();
          }
        }
        return null;
      }));
    }
    for (Future future : futures) {
      future.get();
    }
    cli.println("Finished purging: cluster=" + cluster);
  }

  private void stopReplica(final int replica, final Config config, final List<Config.Shard> shards,
                                  final String installDir) throws InterruptedException, ExecutionException {
    List<Future> futures = new ArrayList<>();
    for (int i = 0; i < shards.size(); i++) {
      Config.Shard shard = shards.get(i);
      final List<Config.Replica> replicas = shard.getReplicas();
      for (int j = 0; j < replicas.size(); j++) {
        final int replicaOffset = j;
        if (replicaOffset != replica) {
          continue;
        }
        futures.add(cli.getExecutor().submit((Callable) () -> {
          Config.Replica replica1 = replicas.get(replicaOffset);
          cli.println("Stopping server: address=" + replica1.getString(PUBLIC_ADDRESS_STR) +
              PORT_STR + String.valueOf(replica1.getInt("port")));
          stopServer(config, replica1.getString(PUBLIC_ADDRESS_STR), replica1.getString(PRIVATE_ADDRESS_STR),
              String.valueOf(replica1.getInt("port")), installDir);
          cli.println("Stopped server: address=" + replica1.getString(PUBLIC_ADDRESS_STR) +
              PORT_STR + String.valueOf(replica1.getInt("port")));
          return null;
        }));
      }
    }
    for (Future future : futures) {
      future.get();
    }

    cli.println("Stopped replica: replica=" + replica);
  }

  void stopShard(int shardOffset) throws IOException, InterruptedException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }
    Config config = cli.getConfig(cluster);
    String installDir = config.getString(INSTALL_DIRECTORY_STR);
    installDir = cli.resolvePath(installDir);
    List<Config.Shard> shards = config.getShards();
    Config.Shard shard = shards.get(shardOffset);
    List<Config.Replica> replicas = shard.getReplicas();
    for (int j = 0; j < replicas.size(); j++) {
      Config.Replica replica = replicas.get(j);
      stopServer(config, replica.getString(PUBLIC_ADDRESS_STR), replica.getString(PRIVATE_ADDRESS_STR),
          String.valueOf(replica.getInt("port")), installDir);
    }
  }

  void stopCluster() throws IOException, InterruptedException, ExecutionException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }
    Config config = cli.getConfig(cluster);
    final String installDir = cli.resolvePath(config.getString(INSTALL_DIRECTORY_STR));
    List<Config.Shard> shards = config.getShards();
    List<Future> futures = new ArrayList<>();
    for (int i = 0; i < shards.size(); i++) {
      Config.Shard shard = shards.get(i);
      final List<Config.Replica> replicas = shard.getReplicas();
      for (int j = 0; j < replicas.size(); j++) {
        final int replicaOffset = j;
        futures.add(cli.getExecutor().submit((Callable) () -> {
          Config.Replica replica = replicas.get(replicaOffset);
          cli.println("Stopping server: address=" + replica.getString(PUBLIC_ADDRESS_STR) +
              PORT_STR + replica.getInt("port"));
          stopServer(config, replica.getString(PUBLIC_ADDRESS_STR),
              replica.getString(PRIVATE_ADDRESS_STR), String.valueOf(replica.getInt("port")), installDir);
          cli.println("Stopped server: address=" + replica.getString(PUBLIC_ADDRESS_STR) +
              PORT_STR + replica.getInt("port"));
          return null;
        }));
      }
    }
    for (Future future : futures) {
      future.get();
    }

    cli.println("Stopped cluster");
  }


  void startShard(int shardOffset) throws IOException, InterruptedException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }
    Config config = cli.getConfig(cluster);
    String installDir = config.getString(INSTALL_DIRECTORY_STR);
    installDir = cli.resolvePath(installDir);
    List<Config.Shard> shards = config.getShards();
    Config.Shard shard = shards.get(shardOffset);
    List<Config.Replica> replicas = shard.getReplicas();
    for (int j = 0; j < replicas.size(); j++) {
      Config.Replica replica = replicas.get(j);
      stopServer(config, replica.getString(PUBLIC_ADDRESS_STR), replica.getString(PRIVATE_ADDRESS_STR),
          String.valueOf(replica.getInt("port")), installDir);
    }
    Thread.sleep(2000);
    final AtomicReference<Double> lastTotalGig = new AtomicReference<>(0d);
    replicas = shard.getReplicas();
    for (int j = 0; j < replicas.size(); j++) {
      Config.Replica replica = replicas.get(j);
      startServer(config, replica.getString(PUBLIC_ADDRESS_STR), replica.getString(PRIVATE_ADDRESS_STR),
          String.valueOf(replica.getInt("port")), installDir, cluster, false, lastTotalGig);
    }
    cli.println("Finished starting servers");
  }

  void reconfigureCluster() throws SQLException, ClassNotFoundException, IOException, InterruptedException, ExecutionException {
    String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }
    cli.closeConnection();
    cli.initConnection();
    deploy();

    ReconfigureResults results = cli.getConn().reconfigureCluster();
    if (!results.isHandedOffToMaster()) {
      cli.println("Must start servers to reconfigure the cluster");
    }
    else {
      int shardCount = results.getShardCount();
      if (shardCount > 0) {
        Config config = cli.getConfig(cluster);
        List<Config.Shard> shards = config.getShards();
        int startedCount = 0;
        for (int i = shards.size() - 1; i >= 0; i--) {
          startShard(i);
          if (++startedCount >= shardCount) {
            break;
          }
        }
      }
    }
    cli.println("Finished reconfiguring cluster");
  }

  void startServer(String command) throws InterruptedException, IOException {
    final String cluster = cli.getCurrCluster();
    if (cluster == null) {
      cli.println(ERROR_NOT_USING_A_CLUSTER_STR);
      return;
    }

    command = command.trim();
    String[] parts = command.split("\\s+");
    final int shard = Integer.parseInt(parts[2]);
    final int replica = Integer.parseInt(parts[3]);
    boolean disable = false;
    if (parts.length > 4) {
      disable = "disable".equals(parts[4]);
    }

    Config config = cli.getConfig(cluster);
    final String installDir = cli.resolvePath(config.getString(INSTALL_DIRECTORY_STR));
    List<Config.Shard> shards = config.getShards();
    final List<Config.Replica> masterReplica = shards.get(shard).getReplicas();
    final Config.Replica currReplica = masterReplica.get(replica);

    stopServer(config, currReplica.getString(PUBLIC_ADDRESS_STR), currReplica.getString(PRIVATE_ADDRESS_STR),
        String.valueOf(currReplica.getInt("port")), installDir);

    Thread.sleep(2000);

    startServer(config, currReplica.getString(PUBLIC_ADDRESS_STR), currReplica.getString(PRIVATE_ADDRESS_STR),
        String.valueOf(currReplica.getInt("port")), installDir, cluster, disable, new AtomicReference<Double>(0d));

    monitorServerStartupProgress(cli, shard, replica, currReplica);

    ComObject cobj = new ComObject(1);
    cli.getConn().send("MonitorManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
    cli.getConn().send("OSStatsManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
    cli.getConn().send("StreamManager:initConnection", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);
  }

  private static void monitorServerStartupProgress(Cli cli, int shard, int replica, Config.Replica currReplica) throws InterruptedException {
    final AtomicBoolean ok = new AtomicBoolean();
    while (!ok.get()) {
      final ComObject cobj = new ComObject(3);
      cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
      cobj.put(ComObject.Tag.SCHEMA_VERSION, 1);
      cobj.put(ComObject.Tag.METHOD, DATABASE_SERVER_HEALTH_CHECK_STR);

      Thread thread = new Thread(() -> {
        try {
          cli.initConnection();

          byte[] bytes = cli.getConn().send(null, shard, replica, cobj, ConnectionProxy.Replica.MASTER, true);
          ComObject retObj = new ComObject(bytes);
          String retStr = retObj.getString(ComObject.Tag.STATUS);
          if (retStr.equals(STATUS_OK_STR)) {
            ok.set(true);
            return;
          }
        }
        catch (Exception e) {
          ComObject cobj1 = new ComObject(3);
          cobj1.put(ComObject.Tag.DB_NAME, NONE_STR);
          cobj1.put(ComObject.Tag.SCHEMA_VERSION, 1);
          cobj1.put(ComObject.Tag.METHOD, DATABASE_SERVER_GET_RECOVER_PROGRESS_STR);

          try {
            byte[] bytes = cli.getConn().send(null, shard, replica, cobj1, ConnectionProxy.Replica.SPECIFIED, true);
            ComObject retObj = new ComObject(bytes);
            double percentComplete = retObj.getDouble(ComObject.Tag.PERCENT_COMPLETE);
            String stage = retObj.getString(ComObject.Tag.STAGE);
            Boolean error = retObj.getBoolean(ComObject.Tag.ERROR);

            percentComplete *= 100d;
            cli.println(WAITING_FOR_STR + currReplica.getString(PRIVATE_ADDRESS_STR) + " to start: stage=" +
                stage + ", " + String.format("%.2f", percentComplete) + "%" + (error != null && error ? ERROR_TRUE_STR : ""));
            try {
              Thread.sleep(2000);
            }
            catch (InterruptedException e1) {
              Thread.currentThread().interrupt();
              return;
            }
          }
          catch (Exception e1) {
            cli.println(WAITING_FOR_STR + currReplica.getString(PRIVATE_ADDRESS_STR) + " to start: percentComplete=?");
            try {
              Thread.sleep(2000);
            }
            catch (InterruptedException e2) {
              Thread.currentThread().interrupt();
              return;
            }
          }
        }
      });
      thread.start();
      thread.join(5000);
      thread.interrupt();
    }
  }

  private void rollingRestart(Config config, List<Config.Shard> shards, String installDir, String cluster) throws InterruptedException, ExecutionException, IOException, SQLException, ClassNotFoundException {
    int replicaCount = cli.getConn().getReplicaCount();

    boolean allHealthy = cli.healthCheck();
    if (!allHealthy) {
      cli.println("At least one server is not healthy. Cannot proceed with restart");
      return;
    }

    cli.initConnection();

    for (int i = 0; i < replicaCount; i++) {
      Thread.sleep(5000);
      changeMasters((i + 1) % replicaCount);
      Thread.sleep(5000);
      markReplicaDead(i);
      try {
        Thread.sleep(5000);
        stopReplica(i, config, shards, installDir);
        Thread.sleep(5000);
        startReplica(i, config, shards, installDir, cluster);
      }
      finally {
        markReplicaAlive(i);
      }
      cli.println("Finished restarting replica: replica=" + i);
    }
  }

  private void changeMasters(int newReplica) {
    while (true) {
      try {
        cli.getConn().syncSchema();

        ComObject cobj = new ComObject(4);
        cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
        cobj.put(ComObject.Tag.SCHEMA_VERSION, cli.getConn().getSchemaVersion());
        cobj.put(ComObject.Tag.METHOD, "DatabaseServer:promoteEntireReplicaToMaster");
        cobj.put(ComObject.Tag.REPLICA, newReplica);
        cli.getConn().sendToMaster(cobj);
        break;
      }
      catch (Exception e) {
        cli.printException(e);
      }
    }
  }

  private void markReplicaAlive(int replica) {
    while (true) {
      try {
        cli.getConn().syncSchema();

        ComObject cobj = new ComObject(4);
        cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
        cobj.put(ComObject.Tag.SCHEMA_VERSION, cli.getConn().getSchemaVersion());
        cobj.put(ComObject.Tag.METHOD, "DatabaseServer:markReplicaAlive");
        cobj.put(ComObject.Tag.REPLICA, replica);
        cli.getConn().sendToMaster(cobj);
        break;
      }
      catch (Exception e) {
        cli.printException(e);
      }
    }
  }

  private void markReplicaDead(int replica) {
    while (true) {
      try {
        cli.getConn().syncSchema();

        ComObject cobj = new ComObject(4);
        cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
        cobj.put(ComObject.Tag.SCHEMA_VERSION, cli.getConn().getSchemaVersion());
        cobj.put(ComObject.Tag.METHOD, "DatabaseServer:markReplicaDead");
        cobj.put(ComObject.Tag.REPLICA, replica);
        cli.getConn().sendToMaster(cobj);
        break;
      }
      catch (Exception e) {
        cli.printException(e);
      }
    }
  }

  public void disableServer(String command) throws SQLException, ClassNotFoundException {
    command = command.trim();
    String[] parts = command.split("\\s+");
    final int shard = Integer.parseInt(parts[2]);
    final int replica = Integer.parseInt(parts[3]);

    cli.initConnection();

    ComObject cobj = new ComObject(2);
    cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
    cobj.put(ComObject.Tag.SCHEMA_VERSION, cli.getConn().getSchemaVersion());
    byte[] bytes = cli.getConn().send("DatabaseServer:disableServer", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);

  }


  public void enableServer(String command) throws SQLException, ClassNotFoundException {
    command = command.trim();
    String[] parts = command.split("\\s+");
    final int shard = Integer.parseInt(parts[2]);
    final int replica = Integer.parseInt(parts[3]);

    cli.initConnection();

    ComObject cobj = new ComObject(2);
    cobj.put(ComObject.Tag.DB_NAME, NONE_STR);
    cobj.put(ComObject.Tag.SCHEMA_VERSION, cli.getConn().getSchemaVersion());
    byte[] bytes = cli.getConn().send("DatabaseServer:enableServer", shard, replica, cobj, ConnectionProxy.Replica.SPECIFIED);

  }
}
