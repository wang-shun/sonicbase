package com.sonicbase.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonicbase.client.DatabaseClient;
import com.sonicbase.common.*;
import com.sonicbase.index.AddressMap;
import com.sonicbase.index.Index;
import com.sonicbase.index.Indices;
import com.sonicbase.jdbcdriver.ConnectionProxy;
import com.sonicbase.procedure.*;
import com.sonicbase.query.DatabaseException;
import com.sonicbase.schema.IndexSchema;
import com.sonicbase.schema.TableSchema;
import com.sonicbase.common.*;
import com.sonicbase.procedure.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.execute.Execute;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.jetbrains.annotations.Nullable;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import java.io.*;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * User: lowryda
 * Date: 12/27/13
 * Time: 4:39 PM
 */
public class DatabaseServer {

  public static final String SONICBASE_SYS_DB_STR = "_sonicbase_sys";
  public static Object deathOverrideMutex = new Object();
  public static boolean[][] deathOverride;
  private Logger logger;
  private static org.apache.log4j.Logger errorLogger = org.apache.log4j.Logger.getLogger("com.sonicbase.errorLogger");
  private static org.apache.log4j.Logger clientErrorLogger = org.apache.log4j.Logger.getLogger("com.sonicbase.clientErrorLogger");

  public static final boolean USE_SNAPSHOT_MGR_OLD = true;
  public static final boolean ENABLE_RECORD_COMPRESSION = false;
  private AtomicLong commandCount = new AtomicLong();
  private int port;
  private String host;
  private String cluster;

  public static final String LICENSE_KEY = "CPuDJRkHB3nq45LObWTCHLNzwWFn8bUT";
  public static final String FOUR_SERVER_LICENSE = "15443f8a6727fcd935fad36afcd9125e";
  public AtomicBoolean isRunning;
  private ThreadPoolExecutor executor;
  private boolean compressRecords = false;
  private boolean useUnsafe;
  private String gclog;
  private String xmx;
  private String installDir;
  private boolean throttleInsert;
  private DeleteManager deleteManager;
  private ReentrantReadWriteLock batchLock = new ReentrantReadWriteLock();
  private ReentrantReadWriteLock.ReadLock batchReadLock = batchLock.readLock();
  private ReentrantReadWriteLock.WriteLock batchWriteLock = batchLock.writeLock();
  private AtomicInteger batchRepartCount = new AtomicInteger();
  private boolean usingMultipleReplicas = false;
  private AWSClient awsClient;
  private boolean onlyQueueCommands;
  private boolean applyingQueuesAndInteractive;
  private MethodInvoker methodInvoker;
  private AddressMap addressMap;
  private BulkImportManager bulkImportManager;
  private StreamManager streamManager;
  private boolean waitingForServersToStart = false;
  private Connection sysConnection;
  private Thread streamsConsumerMonitorthread;

  private Connection connectionForStoredProcedure;
  private Timer isStreamingStartedTimer;
  private MonitorManager monitorManager;
  private OSStatsManager osStatsManager;
  private BackupManager backupManager;
  private HttpServer httpServer;
  private Integer httpPort;
  private AtomicBoolean isRecovered;
  private MasterManager masterManager;
  private LicenseManager licenseManager;

  private DatabaseCommon common = new DatabaseCommon();
  private AtomicReference<DatabaseClient> client = new AtomicReference<>();
  private PartitionManager partitionManager;
  private AtomicLong nextRecordId = new AtomicLong();
  private int recordsByIdPartitionCount = 50000;
  private ObjectNode config;
  private DatabaseClient.Replica role;
  private int shard;
  private int shardCount;
  private Map<String, Indices> indexes = new ConcurrentHashMap<>();
  private LongRunningCalls longRunningCommands;

  private String dataDir;
  private int replica;
  private int replicationFactor;
  private String masterAddress;
  private int masterPort;
  private UpdateManager updateManager;
  private SnapshotManager snapshotManager;
  private TransactionManager transactionManager;
  private ReadManager readManager;
  private LogManager logManager;
  private SchemaManager schemaManager;


  public DatabaseServer() {
  }

  public static boolean[][] getDeathOverride() {
    return deathOverride;
  }

  public void shutdown() {
    try {
      shutdown = true;

      if (httpServer != null) {
        httpServer.shutdown();
      }
      if (monitorManager != null) {
        monitorManager.shutdown();
      }
      if (isStreamingStartedTimer != null) {
        isStreamingStartedTimer.cancel();
      }

      if (backupManager != null) {
        backupManager.shutdown();
      }

      if (streamsConsumerMonitorthread != null) {
        streamsConsumerMonitorthread.interrupt();
        streamsConsumerMonitorthread.join();
      }
      streamManager.shutdown();
      licenseManager.shutdown();

      if (longRunningCommands != null) {
        longRunningCommands.shutdown();
      }
      shutdownDeathMonitor();
      shutdownRepartitioner();

      deleteManager.shutdown();
      snapshotManager.shutdown();
      logManager.shutdown();
      executor.shutdownNow();
      methodInvoker.shutdown();
      client.get().shutdown();
      if (sysConnection != null) {
        sysConnection.close();
      }
      if (connectionForStoredProcedure != null) {
        connectionForStoredProcedure.close();
      }
      readManager.shutdown();
      transactionManager.shutdown();
      updateManager.shutdown();
      bulkImportManager.shutdown();
      if (osStatsManager != null) {
        osStatsManager.shutdown();
      }

      executor.shutdownNow();
    }
    catch (Exception e) {
      throw new DatabaseException("Error shutting down DatabaseServer", e);
    }
  }

  public MethodInvoker getMethodInvoker() {
    return methodInvoker;
  }

  public org.apache.log4j.Logger getErrorLogger() {
    return errorLogger;
  }

  public org.apache.log4j.Logger getClientErrorLogger() {
    return clientErrorLogger;
  }

  public void setConfig(
      final ObjectNode config, String cluster, String host, int port, AtomicBoolean isRunning, AtomicBoolean isRecovered, String gclog, String xmx,
      boolean overrideProLicense) {
    setConfig(config, cluster, host, port, false, isRunning, isRecovered, false, gclog, xmx, overrideProLicense);
  }

  public void setConfig(
      final ObjectNode config, String cluster, String host, int port,
      boolean unitTest, AtomicBoolean isRunning, AtomicBoolean isRecovered, String gclog, boolean overrideProLicense) {
    setConfig(config, cluster, host, port, unitTest, isRunning, isRecovered, false, gclog, null, overrideProLicense);
  }

  public void setConfig(
      final ObjectNode config, String cluster, String host, int port,
      boolean unitTest, AtomicBoolean isRunning, AtomicBoolean isRecovered, boolean skipLicense, String gclog, String xmx, boolean overrideProLicense) {

    this.isRunning = isRunning;
    this.isRecovered = isRecovered;
    this.config = config;
    this.cluster = cluster;
    this.host = host;
    this.port = port;
    this.gclog = gclog;
    this.xmx = xmx;

    ObjectNode databaseDict = config;
    this.dataDir = databaseDict.get("dataDirectory").asText();
    this.dataDir = dataDir.replace("$HOME", System.getProperty("user.home"));
    this.installDir = databaseDict.get("installDirectory").asText();
    this.installDir = installDir.replace("$HOME", System.getProperty("user.home"));
    ArrayNode shards = databaseDict.withArray("shards");
    int replicaCount = shards.get(0).withArray("replicas").size();
    if (replicaCount > 1) {
      usingMultipleReplicas = true;
    }

    ObjectNode firstServer = (ObjectNode) shards.get(0).withArray("replicas").get(0);
    ServersConfig serversConfig = null;
    executor = ThreadUtil.createExecutor(Runtime.getRuntime().availableProcessors() * 128, "Sonicbase DatabaseServer Thread");

    if (databaseDict.has("compressRecords")) {
      compressRecords = databaseDict.get("compressRecords").asBoolean();
    }
    if (databaseDict.has("useUnsafe")) {
      useUnsafe = databaseDict.get("useUnsafe").asBoolean();
    }
    else {
      useUnsafe = true;
    }

    this.masterAddress = firstServer.get("privateAddress").asText();
    this.masterPort = firstServer.get("port").asInt();

    if (firstServer.get("privateAddress").asText().equals(host) && firstServer.get("port").asLong() == port) {
      this.shard = 0;
      this.replica = 0;
      common.setShard(0);
      common.setReplica(0);

    }
    boolean isInternal = false;
    if (databaseDict.has("clientIsPrivate")) {
      isInternal = databaseDict.get("clientIsPrivate").asBoolean();
    }
    boolean optimizedForThroughput = true;
    if (databaseDict.has("optimizeReadsFor")) {
      String text = databaseDict.get("optimizeReadsFor").asText();
      if (!text.equalsIgnoreCase("totalThroughput")) {
        optimizedForThroughput = false;
      }
    }
    serversConfig = new ServersConfig(cluster, shards, replicationFactor, isInternal, optimizedForThroughput);

    initServersForUnitTest(host, port, unitTest, serversConfig);

    this.replica = serversConfig.getThisReplica(host, port);

    common.setShard(serversConfig.getThisShard(host, port));
    common.setReplica(this.replica);
    common.setServersConfig(serversConfig);
    this.shard = common.getShard();
    this.shardCount = serversConfig.getShardCount();

    common.setServersConfig(serversConfig);

    logger = new Logger(null /*getDatabaseClient()*/, shard, replica);
    logger.info("config=" + config.toString());

    logger.info("useUnsafe=" + useUnsafe);

    common.setHaveProLicense(true);

    this.awsClient = new AWSClient(client.get());

    this.licenseManager = new LicenseManager(this, overrideProLicense);

    addressMap = new AddressMap(this);

    this.deleteManager = new DeleteManager(this);

    this.updateManager = new UpdateManager(this);
    this.snapshotManager = new SnapshotManager(this);

    this.transactionManager = new TransactionManager(this);
    this.readManager = new ReadManager(this);
    this.logManager = new LogManager(this, new File(dataDir, "log"));
    this.schemaManager = new SchemaManager(this);
    this.bulkImportManager = new BulkImportManager(this);
    this.monitorManager = new MonitorManager(this);
    this.backupManager = new BackupManager(this);
    this.osStatsManager = new OSStatsManager(this);
    this.masterManager = new MasterManager(this);
    this.osStatsManager.startStatsMonitoring();
    this.partitionManager = new PartitionManager(this, common);
    this.streamManager = new StreamManager(this);

    this.methodInvoker = new MethodInvoker(this, bulkImportManager, deleteManager, snapshotManager, updateManager,
        transactionManager, readManager, logManager, schemaManager, monitorManager, backupManager, osStatsManager, masterManager);
    this.methodInvoker.registerMethodProvider("BackupManager", backupManager);
    this.methodInvoker.registerMethodProvider("BulkImportManager", bulkImportManager);
    this.methodInvoker.registerMethodProvider("DeleteManager", deleteManager);
    this.methodInvoker.registerMethodProvider("LicenseManager", licenseManager);
    this.methodInvoker.registerMethodProvider("LogManager", logManager);
    this.methodInvoker.registerMethodProvider("MasterManager", masterManager);
    this.methodInvoker.registerMethodProvider("MonitorManager", monitorManager);
    this.methodInvoker.registerMethodProvider("OSStatsManager", osStatsManager);
    this.methodInvoker.registerMethodProvider("PartitionManager", partitionManager);
    this.methodInvoker.registerMethodProvider("ReadManager", readManager);
    this.methodInvoker.registerMethodProvider("SchemaManager", schemaManager);
    this.methodInvoker.registerMethodProvider("SnapshotManager", snapshotManager);
    this.methodInvoker.registerMethodProvider("StreamManager", streamManager);
    this.methodInvoker.registerMethodProvider("TransactionManager", transactionManager);
    this.methodInvoker.registerMethodProvider("UpdateManager", updateManager);
    this.methodInvoker.registerMethodProvider("DatabaseServer", this);

    outer:
    for (int i = 0; i < shards.size(); i++) {
      ArrayNode replicas = (ArrayNode) shards.get(i).withArray("replicas");
      for (int j = 0; j < replicas.size(); j++) {
        JsonNode replica = replicas.get(j);
        if (replica.get("privateAddress").asText().equals(host) && replica.get("port").asInt() == port) {
          if (replica.get("httpPort") != null) {
            httpPort = replica.get("httpPort").asInt();

            this.httpServer = new HttpServer(this);
            this.httpServer.startMonitorServer(host, httpPort);
            break outer;
          }
        }
      }
    }

    this.replicationFactor = shards.get(0).withArray("replicas").size();

    backupManager.scheduleBackup();

    while (!shutdown) {
      try {
        if (shard != 0 || replica != 0) {
          syncDbNames();
        }
        break;
      }
      catch (Exception e) {
        logger.error("Error syncing dbs", e);
      }
    }

    List<String> dbNames = getDbNames(dataDir);
    for (String dbName : dbNames) {

      getCommon().addDatabase(dbName);
    }

    if (shard != 0 || replica != 0) {
      getDatabaseClient().syncSchema();
    }
    else {
      common.loadSchema(dataDir);
    }

    common.saveSchema(getClient(), dataDir);

    for (String dbName : dbNames) {
      logger.info("Loaded database schema: dbName=" + dbName + ", tableCount=" + common.getTables(dbName).size());
      getIndices().put(dbName, new Indices());

      schemaManager.addAllIndices(dbName);
    }

    common.setServersConfig(serversConfig);

    longRunningCommands = new LongRunningCalls(this);
    startLongRunningCommands();

    osStatsManager.startMemoryMonitor();

    disable();
    licenseManager.startLicenseValidator();

    logger.info("Started server");


    isStreamingStartedTimer = new Timer("SonicBase IsStreamingStarted Timer");
    isStreamingStartedTimer.schedule(new TimerTask(){
      @Override
      public void run() {
        ComObject cobj = new ComObject();
        cobj.put(ComObject.Tag.method, "StreamManager:isStreamingStarted");

        ComObject retObj = new ComObject(getDatabaseClient().sendToMaster(cobj));
        if (retObj.getBoolean(ComObject.Tag.isStarted)) {
          streamManager.startStreaming(null, false);
        }
      }
    }, 20_000);
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public String getGcLog() {
    return gclog;
  }

  public void setBackupConfig(ObjectNode backup) {
    backupManager.setBackupConfig(backup);
  }

  public static void initDeathOverride(int shardCount, int replicaCount) {
    synchronized (deathOverrideMutex) {
      if (deathOverride == null) {
        deathOverride = new boolean[shardCount][];
        for (int i = 0; i < shardCount; i++) {
          deathOverride[i] = new boolean[replicaCount];
        }
      }
    }
  }

  public int getTestWriteCallCount() {
    return methodInvoker.getTestWriteCallCount();
  }

  public void removeIndices(String dbName, String tableName) {
    if (getIndices() != null) {
      if (getIndices().get(dbName) != null) {
        if (getIndices().get(dbName).getIndices() != null) {
          getIndices().get(dbName).getIndices().remove(tableName);
        }
      }
    }
  }


  private final Object connMutex = new Object();
  public Connection getSysConnection() {
    try {
      ConnectionProxy conn = null;
      try {
        synchronized (connMutex) {
          if (sysConnection != null) {
            return sysConnection;
          }
          ArrayNode array = config.withArray("shards");
          ObjectNode replicaDict = (ObjectNode) array.get(0);
          ArrayNode replicasArray = replicaDict.withArray("replicas");
          JsonNode node = config.get("clientIsPrivate");
          final String address = node != null && node.asBoolean() ?
              replicasArray.get(0).get("privateAddress").asText() :
              replicasArray.get(0).get("publicAddress").asText();
          final int port = replicasArray.get(0).get("port").asInt();

          Class.forName("com.sonicbase.jdbcdriver.Driver");
          conn = new ConnectionProxy("jdbc:sonicbase:" + address + ":" + port, this);
          try {
            if (!((ConnectionProxy) conn).databaseExists(SONICBASE_SYS_DB_STR)) {
              ((ConnectionProxy) conn).createDatabase(SONICBASE_SYS_DB_STR);
            }
          }
          catch (Exception e) {
            if (!ExceptionUtils.getFullStackTrace(e).toLowerCase().contains("database already exists")) {
              throw new DatabaseException(e);
            }
          }

          sysConnection = new ConnectionProxy("jdbc:sonicbase:" + address + ":" + port + "/_sonicbase_sys", this);
        }
      }
      finally {
        if (conn != null) {
          conn.close();
        }
      }

      return sysConnection;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public void startStreamsConsumerMonitor() {
    if (streamsConsumerMonitorthread == null) {
      streamsConsumerMonitorthread = ThreadUtil.createThread(new Runnable() {
        @Override
        public void run() {
          try {
            Connection conn = getSysConnection();
            StreamManager.initStreamsConsumerTable(conn);

            while (!shutdown && !Thread.interrupted()) {
              try {
                StringBuilder builder = new StringBuilder();
                Map<String, Boolean> currState = null;
                try {
                  currState = readStreamConsumerState();
                }
                catch (Exception e) {
                  logger.error("Error reading stream consumer state - will retry: msg=" + e.getMessage());
                  Thread.sleep(2_000);
                  continue;
                }
                for (int shard = 0; shard < shardCount; shard++) {
                  int aliveReplica = -1;
                  for (int replica = 0; replica < replicationFactor; replica++) {
                    boolean dead = common.getServersConfig().getShards()[shard].getReplicas()[replica].isDead();
                    if (!dead) {
                      aliveReplica = replica;
                    }
                  }
                  builder.append(",").append(shard).append(":").append(aliveReplica);
                  Boolean currActive = currState.get(shard + ":" + aliveReplica);
                  if (currActive == null || !currActive) {
                    setStreamConsumerState(shard, aliveReplica);
                  }
                }
                logger.info("active streams consumers: " + builder.toString());
                Thread.sleep(10_000);
              }
              catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
              }
              catch (Exception e) {
                logger.error("Error in stream consumer monitor", e);
              }
            }
          }
          finally {
            streamsConsumerMonitorthread = null;
          }
        }
      }, "SonicBase Streams Consumer Monitor Thread");
      streamsConsumerMonitorthread.start();
    }
  }

  private void setStreamConsumerState(int shard, int replica) {
    try {
      for (int i = 0; i < getReplicationFactor(); i++) {
        PreparedStatement stmt = null;
        try {
          stmt = sysConnection.prepareStatement("insert ignore into streams_consumer_state (shard, replica, active) VALUES (?, ?, ?)");
          stmt.setInt(1, shard);
          stmt.setInt(2, i);
          stmt.setBoolean(3, replica == i);
          int count = stmt.executeUpdate();
          stmt.close();
        }
        finally {
          stmt.close();
        }
      }
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  private Map<String, Boolean> readStreamConsumerState() {
    Map<String, Boolean> ret = new HashMap<>();
    PreparedStatement stmt = null;
    try {
      stmt = sysConnection.prepareStatement("select * from streams_consumer_state");
      ResultSet rs = stmt.executeQuery();
      while (rs.next()) {
        int shard = rs.getInt("shard");
        int replica = rs.getInt("replica");
        boolean isActive = rs.getBoolean("active");
        ret.put(shard + ":" + replica, isActive);
      }
      return ret;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
    finally {
      if (stmt != null) {
        try {
          stmt.close();
        }
        catch (SQLException e) {
          throw new DatabaseException(e);
        }
      }
    }
  }

  public void shutdownDeathMonitor() {
    synchronized (deathMonitorMutex) {
      logger.info("Stopping death monitor");
      shutdownDeathMonitor = true;
      try {
        if (deathMonitorThreads != null) {
          for (int i = 0; i < shardCount; i++) {
            for (int j = 0; j < replicationFactor; j++) {
              if (deathMonitorThreads[i] != null && deathMonitorThreads[i][j] != null) {
                deathMonitorThreads[i][j].interrupt();
              }
            }
          }
        }
      }
      catch (Exception e) {
        logger.error("Error shutting down death monitor", e);
      }
      if (deathReportThread != null) {
        deathReportThread.interrupt();
      }
      deathReportThread = null;
      deathMonitorThreads = null;
    }
  }

  private Thread[][] deathMonitorThreads = null;
  boolean shutdownDeathMonitor = false;
  private Object deathMonitorMutex = new Object();
  private Thread deathReportThread = null;

  public void startDeathMonitor() {
    synchronized (deathMonitorMutex) {

      deathReportThread = ThreadUtil.createThread(new Runnable() {
        @Override
        public void run() {
          while (!shutdown) {
            try {
              Thread.sleep(10000);
              StringBuilder builder = new StringBuilder();
              for (int i = 0; i < shardCount; i++) {
                for (int j = 0; j < replicationFactor; j++) {
                  builder.append("[").append(i).append(",").append(j).append("=");
                  builder.append(common.getServersConfig().getShards()[i].getReplicas()[j].isDead() ? "dead" : "alive").append("]");
                }
              }
              logger.info("Death status=" + builder.toString());

              if (replicationFactor > 1 && masterManager.isNoLongerMaster()) {
                logger.info("No longer master. Shutting down resources");
                licenseManager.shutdownMasterLicenseValidator();
                shutdownDeathMonitor();
                shutdownRepartitioner();


                masterManager.shutdownFixSchemaTimer();

                monitorManager.shutdown();

                if (streamsConsumerMonitorthread != null) {
                  streamsConsumerMonitorthread.interrupt();
                  streamsConsumerMonitorthread.join();
                  streamsConsumerMonitorthread = null;
                }

                break;
              }
            }
            catch (InterruptedException e) {
              break;
            }
            catch (Exception e) {
              logger.error("Error in death reporting thread", e);
            }
          }
        }
      }, "SonicBase Death Reporting Thread");
      deathReportThread.start();

      shutdownDeathMonitor = false;
      logger.info("Starting death monitor");
      deathMonitorThreads = new Thread[shardCount][];
      for (int i = 0; i < shardCount; i++) {
        final int shard = i;
        deathMonitorThreads[i] = new Thread[replicationFactor];
        for (int j = 0; j < replicationFactor; j++) {
          final int replica = j;
          if (shard == this.shard && replica == this.replica) {
            boolean wasDead = common.getServersConfig().getShards()[shard].getReplicas()[replica].isDead();
            if (wasDead) {
              AtomicBoolean isHealthy = new AtomicBoolean(true);
              handleHealthChange(isHealthy, wasDead, true, shard, replica);
            }
            continue;
          }
          deathMonitorThreads[i][j] = ThreadUtil.createThread(new Runnable() {
            @Override
            public void run() {
              while (!shutdownDeathMonitor) {
                try {
                  Thread.sleep(deathOverride == null ? 2000 : 50);
                  AtomicBoolean isHealthy = new AtomicBoolean();
                  for (int i = 0; i < 5; i++) {
                    checkHealthOfServer(shard, replica, isHealthy, true);
                    if (isHealthy.get()) {
                      break;
                    }
                    Thread.sleep(1_000);
                  }
                  boolean wasDead = common.getServersConfig().getShards()[shard].getReplicas()[replica].isDead();
                  boolean changed = false;
                  if (wasDead && isHealthy.get()) {
                    changed = true;
                  }
                  else if (!wasDead && !isHealthy.get()) {
                    changed = true;
                  }
                  if (changed && isHealthy.get()) {
                    ComObject cobj = new ComObject();
                    cobj.put(ComObject.Tag.dbName, "__none__");
                    cobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
                    cobj.put(ComObject.Tag.method, "DatabaessServer:prepareToComeAlive");

                    getDatabaseClient().send(null, shard, replica, cobj, DatabaseClient.Replica.specified, true);
                  }
                  handleHealthChange(isHealthy, wasDead, changed, shard, replica);
                }
                catch (InterruptedException e) {
                  break;
                }
                catch (Exception e) {
                  if (!shutdownDeathMonitor) {
                    logger.error("Error in death monitor thread: shard=" + shard + ", replica=" + replica, e);
                  }
                }
              }
            }
          }, "SonicBase Death Monitor Thread: shard=" + i + ", replica=" + j);
          deathMonitorThreads[i][j].start();
        }
      }
    }
  }

  private void handleHealthChange(AtomicBoolean isHealthy, boolean wasDead, boolean changed, int shard, int replica) {
    synchronized (common) {
      if (wasDead && isHealthy.get()) {
        common.getServersConfig().getShards()[shard].getReplicas()[replica].setDead(false);
        changed = true;
      }
      else if (!wasDead && !isHealthy.get()) {
        common.getServersConfig().getShards()[shard].getReplicas()[replica].setDead(true);
        changed = true;
      }
    }
    if (changed) {
      logger.info("server health changed: shard=" + shard + ", replica=" + replica + ", isHealthy=" + isHealthy.get());
      common.saveSchema(getClient(), getDataDir());
      pushSchema();
    }
  }

  private int replicaDeadForRestart = -1;

  public void checkHealthOfServer(final int shard, final int replica, final AtomicBoolean isHealthy, final boolean ignoreDeath) throws InterruptedException {

    if (deathOverride != null) {
      isHealthy.set(!deathOverride[shard][replica]);
      return;
    }

    if (replicaDeadForRestart == replica) {
      isHealthy.set(false);
      return;
    }

    if (shard == this.shard && replica == this.replica) {
      isHealthy.set(true);
      return;
    }

    final AtomicBoolean finished = new AtomicBoolean();
    isHealthy.set(false);
    Thread checkThread = ThreadUtil.createThread(new Runnable() {
      private ComObject recoverStatus;

      @Override
      public void run() {
        int backoff = 100;
          ServersConfig.Host host = common.getServersConfig().getShards()[shard].getReplicas()[replica];
          boolean wasDead = host.isDead();
          try {
            ComObject cobj = new ComObject();
            cobj.put(ComObject.Tag.dbName, "__none__");
            cobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
            cobj.put(ComObject.Tag.method, "DatabaseServer:healthCheck");
            byte[] bytes = getDatabaseClient().send(null, shard, replica, cobj, DatabaseClient.Replica.specified, true);
            ComObject retObj = new ComObject(bytes);
            if (retObj.getString(ComObject.Tag.status).equals("{\"status\" : \"ok\"}")) {
              isHealthy.set(true);
            }
            return;
          }
          catch (Exception e) {
            if (e instanceof DeadServerException) {//-1 != index) {
              logger.error("Error checking health of server - dead server: shard=" + shard + ", replica=" + replica);
            }
            else {
              logger.error("Error checking health of server: shard=" + shard + ", replica=" + replica, e);
            }
          }
          finally {
            finished.set(true);
          }
      }
    }, "SonicBase Check Health of Server Thread");
    checkThread.start();

    int i = 0;
    while (!finished.get()) {
      Thread.sleep(deathOverride == null ? 100 : 100);
      if (i++ > 50) {
        checkThread.interrupt();
        break;
      }
    }
  }

  public AWSClient getAWSClient() {
    return awsClient;
  }

  public static void disable() {
    try {
      SSLContext sslc = SSLContext.getInstance("TLS");
      TrustManager[] trustManagerArray = {new NullX509TrustManager()};
      sslc.init(null, trustManagerArray, null);
      HttpsURLConnection.setDefaultSSLSocketFactory(sslc.getSocketFactory());
      HttpsURLConnection.setDefaultHostnameVerifier(new NullHostnameVerifier());
    }
    catch (Exception e) {
      e.printStackTrace();
    }
  }

  public ThreadPoolExecutor getExecutor() {
    return executor;
  }

  public Thread[][] getDeathMonitorThreads() {
    return deathMonitorThreads;
  }

  public void setReplicaDeadForRestart(int replicaDeadForRestart) {
    this.replicaDeadForRestart = replicaDeadForRestart;
  }

  public boolean isApplyingQueuesAndInteractive() {
    return applyingQueuesAndInteractive;
  }

  public boolean shouldDisableNow() {
    return licenseManager.disableNow();
  }

  public boolean isUsingMultipleReplicas() {
    return usingMultipleReplicas;
  }

  public boolean onlyQueueCommands() {
    return onlyQueueCommands;
  }

  public String getInstallDir() {
    return installDir;
  }

  public boolean haveProLicense() {
    return licenseManager.haveProLicense();
  }

  public Logger getLogger() {
    return logger;
  }

  public StreamManager getStreamManager() {
    return streamManager;
  }

  public SnapshotManager getSnapshotManager() {
    return snapshotManager;
  }

  public ComObject getRecoverProgress(ComObject cobj, boolean replayedCommand) {
    ComObject retObj = new ComObject();
    if (waitingForServersToStart) {
      retObj.put(ComObject.Tag.percentComplete, 0d);
      retObj.put(ComObject.Tag.stage, "waitingForServersToStart");
    }
    else if (snapshotManager.isRecovering()) {
      snapshotManager.getPercentRecoverComplete(retObj);
    }
    else if (!getDeleteManager().isForcingDeletes()) {
      retObj.put(ComObject.Tag.percentComplete, logManager.getPercentApplyQueuesComplete());
      retObj.put(ComObject.Tag.stage, "applyingLogs");
    }
    else {
      retObj.put(ComObject.Tag.percentComplete, getDeleteManager().getPercentDeleteComplete());
      retObj.put(ComObject.Tag.stage, "forcingDeletes");
    }
    Exception error = snapshotManager.getErrorRecovering();
    if (error != null) {
      retObj.put(ComObject.Tag.error, true);
    }
    return retObj;

  }


  public Parameters getParametersFromStoredProcedure(Execute execute) {
    ExpressionList expressions = execute.getExprList();
    Object[] parmsArray = new Object[expressions.getExpressions().size()];
    int offset = 0;
    for (Expression expression : expressions.getExpressions()) {
      if (expression instanceof StringValue) {
        parmsArray[offset] = ((StringValue)expression).getValue();
      }
      else if (expression instanceof LongValue) {
        parmsArray[offset] = ((LongValue)expression).getValue();
      }
      else if (expression instanceof DateValue) {
        parmsArray[offset] = ((DateValue)expression).getValue();
      }
      else if (expression instanceof DoubleValue) {
        parmsArray[offset] = ((DoubleValue)expression).getValue();
      }
      else if (expression instanceof TimeValue) {
        parmsArray[offset] = ((TimeValue)expression).getValue();
      }
      else if (expression instanceof TimestampValue) {
        parmsArray[offset] = ((TimestampValue)expression).getValue();
      }
      else if (expression instanceof NullValue) {
        parmsArray[offset] = null;
      }
      offset++;
    }
    ParametersImpl parms = new ParametersImpl(parmsArray);
    return parms;
  }

  public ConnectionProxy getConnectionForStoredProcedure(String dbName) throws ClassNotFoundException, SQLException {
    synchronized (this) {
      if (connectionForStoredProcedure != null) {
        return (ConnectionProxy) connectionForStoredProcedure;
      }
      ArrayNode array = config.withArray("shards");
      ObjectNode replicaDict = (ObjectNode) array.get(0);
      ArrayNode replicasArray = replicaDict.withArray("replicas");
      JsonNode node = config.get("clientIsPrivate");
      final String address = node != null && node.asBoolean() ?
          replicasArray.get(0).get("privateAddress").asText() :
          replicasArray.get(0).get("publicAddress").asText();
      final int port = replicasArray.get(0).get("port").asInt();

      Class.forName("com.sonicbase.jdbcdriver.Driver");
      connectionForStoredProcedure = new ConnectionProxy("jdbc:sonicbase:" + address + ":" + port + "/" + dbName, this);

      ((ConnectionProxy)connectionForStoredProcedure).getDatabaseClient().syncSchema();
      return (ConnectionProxy) connectionForStoredProcedure;
    }
  }

  public ComObject executeProcedure(final ComObject cobj, boolean replayedCommand) {
    ConnectionProxy conn = null;
    try {
      if (!common.haveProLicense()) {
        throw new InsufficientLicense("You must have a pro license to use stored procedures");
      }

      String sql = cobj.getString(ComObject.Tag.sql);
      CCJSqlParserManager parser = new CCJSqlParserManager();
      Statement statement = parser.parse(new StringReader(sql));
      if (!(statement instanceof Execute)) {
        throw new DatabaseException("Invalid command: sql=" + sql);
      }
      Execute execute = (Execute) statement;

      Parameters parms = getParametersFromStoredProcedure(execute);

      String dbName = cobj.getString(ComObject.Tag.dbName);
      String className = parms.getString(1);
      StoredProcedure procedure = (StoredProcedure) Class.forName(className).newInstance();
      StoredProcedureContextImpl context = new StoredProcedureContextImpl();
      context.setConfig(config);
      context.setShard(shard);
      context.setReplica(replica);

      conn = getConnectionForStoredProcedure(dbName);
      int viewVersion = conn.getDatabaseClient().getCommon().getSchemaVersion();
      context.setViewVersion(viewVersion);

      context.setConnection(new SonicBaseConnectionImpl(conn));

      long storedProcedureId = cobj.getLong(ComObject.Tag.id);
      context.setStoredProdecureId(storedProcedureId);
      context.setParameters(parms);
      StoredProcedureResponse response = procedure.execute(context);
      if (response == null) {
        return null;
      }
      return ((StoredProcedureResponseImpl)response).serialize();
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public ComObject executeProcedurePrimary(final ComObject cobj, boolean replayedCommand) {
    ThreadPoolExecutor executor = null;
    try {
      if (!common.haveProLicense()) {
        throw new InsufficientLicense("You must have a pro license to use stored procedures");
      }
      String sql = cobj.getString(ComObject.Tag.sql);
      CCJSqlParserManager parser = new CCJSqlParserManager();
      Statement statement = parser.parse(new StringReader(sql));
      if (!(statement instanceof Execute)) {
        throw new DatabaseException("Invalid command: sql=" + sql);
      }
      Execute execute = (Execute) statement;

      Parameters parms = getParametersFromStoredProcedure(execute);

      String dbName = cobj.getString(ComObject.Tag.dbName);
      String className = parms.getString(1);
      StoredProcedure procedure = (StoredProcedure) Class.forName(className).newInstance();
      StoredProcedureContextImpl context = new StoredProcedureContextImpl();
      context.setConfig(config);
      context.setShard(shard);
      context.setReplica(replica);

      Connection conn = getConnectionForStoredProcedure(dbName);

      context.setConnection(new SonicBaseConnectionImpl(conn));

      long storedProcedureId = getDatabaseClient().allocateId(dbName);
      context.setStoredProdecureId(storedProcedureId);
      context.setParameters(parms);
      procedure.init(context);

      cobj.put(ComObject.Tag.method, "DatabaseServer:executeProcedure");
      cobj.put(ComObject.Tag.id, storedProcedureId);

      executor = ThreadUtil.createExecutor(shardCount, "SonicBase executeProcedurePrimary Thread");
      // call all shards
      List<Future> futures = new ArrayList<>();
      for (int i = 0; i < shardCount; i++) {
        final int shard = i;
        futures.add(executor.submit(new Callable(){
          @Override
          public Object call() throws Exception {
            return getDatabaseClient().send(null, shard, 0, cobj, DatabaseClient.Replica.def);
          }
        }));
      }

      List<StoredProcedureResponse> responses = new ArrayList<>();
      for (Future future : futures) {
        byte[] ret = (byte[]) future.get();
        if (ret != null) {
          StoredProcedureResponseImpl response = new StoredProcedureResponseImpl(common, new ComObject(ret));
          responses.add(response);
        }
      }

      StoredProcedureResponseImpl ret = (StoredProcedureResponseImpl) procedure.finalize(context, responses);
      if (ret.getRecords().size() > 50_000) {
        throw new DatabaseException("Too many results returned: allowed=50000, attemptedToReturn=" + ret.getRecords().size());
      }
      return ret.serialize();
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
    finally {
      if (executor != null) {
        executor.shutdownNow();
      }
    }
  }

  public boolean getShutdown() {
    return shutdown;
  }

  public void setIsRunning(boolean isRunning) {
    this.isRunning.set(isRunning);
  }

  public BackupManager getBackupManager() {
    return backupManager;
  }

  public String getXmx() {
    return xmx;
  }

  public OSStatsManager getOSStatsManager() {
    return osStatsManager;
  }

  public MonitorManager getMonitorManager() {
    return monitorManager;
  }

  public MasterManager getMasterManager() {
    return masterManager;
  }

  public LicenseManager getLicenseManager() {
    return licenseManager;
  }

  public boolean getShutdownDeathMonitor() {
    return shutdownDeathMonitor;
  }

  public boolean useUnsafe() {
    return useUnsafe;
  }

  public boolean compressRecords() {
    return compressRecords;
  }

  public void removeIndex(String dbName, String table, String indexName) {
    getIndices().get(dbName).getIndices().get(table).remove(indexName);
  }

  public void setDatabaseClient(DatabaseClient databaseClient) {
    this.client.set(databaseClient);
  }

  private static class NullX509TrustManager implements X509TrustManager {
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    }

    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }

  private static class NullHostnameVerifier implements HostnameVerifier {
    public boolean verify(String hostname, SSLSession session) {
      return true;
    }
  }


  public void setMinSizeForRepartition(int minSizeForRepartition) {
    //partitionManager.setMinSizeForRepartition(minSizeForRepartition);
  }

  public long getCommandCount() {
    return commandCount.get();
  }

  public int getReplicationFactor() {
    return replicationFactor;
  }

  public DatabaseClient getDatabaseClient() {
    synchronized (this.client) {
      if (this.client.get() != null) {
        return this.client.get();
      }
      DatabaseClient client = new DatabaseClient(masterAddress, masterPort, common.getShard(), common.getReplica(), false, common, this);
      this.client.set(client);
      return this.client.get();
    }
  }

  public IndexSchema getIndexSchema(String dbName, String tableName, String indexName) {
    TableSchema tableSchema = common.getTables(dbName).get(tableName);
    IndexSchema indexSchema = null;
    for (int i = 0; i < 10; i++) {
      if (tableSchema == null) {
        getClient().syncSchema();
        tableSchema = common.getTables(dbName).get(tableName);
      }
      if (tableSchema != null) {
        indexSchema = tableSchema.getIndices().get(indexName);
        if (indexSchema == null) {
          getClient().syncSchema();
          indexSchema = tableSchema.getIndices().get(indexName);
          if (indexSchema != null) {
            tableSchema.getIndexes().put(indexName, indexSchema);
            tableSchema.getIndexesById().put(indexSchema.getIndexId(), indexSchema);
            common.saveSchema(getClient(), getDataDir());
          }
        }
      }
      if (indexSchema != null) {
        break;
      }
      break;
    }
    return indexSchema;
  }


  @Nullable
  public Index getIndex(String dbName, String tableName, String indexName) {
    TableSchema tableSchema = common.getTables(dbName).get(tableName);
    ConcurrentHashMap<String, ConcurrentHashMap<String, Index>> indices = getIndices(dbName).getIndices();
    Index index = null;
    for (int i = 0; i < 10; i++) {
      ConcurrentHashMap<String, Index> table = indices.get(tableSchema.getName());
      if (table == null) {
        getClient().syncSchema();
        table = indices.get(tableSchema.getName());
      }
      if (table != null) {
        index = table.get(indexName);
        if (index == null) {
          getClient().syncSchema();
          index = getIndices(dbName).getIndices().get(tableSchema.getName()).get(indexName);
        }
        if (index == null) {
          schemaManager.doCreateIndex(dbName, tableSchema, indexName, tableSchema.getIndices().get(indexName).getFields());
          index = getIndices(dbName).getIndices().get(tableSchema.getName()).get(indexName);
        }
      }
      if (index != null) {
        break;
      }
      break;
//      try {
//        Thread.sleep(1_000);
//      }
//      catch (InterruptedException e) {
//        throw new DatabaseException(e);
//      }
    }
    return index;
  }

  public int getSchemaVersion() {
    return common.getSchemaVersion();
  }

  public DatabaseCommon getCommon() {
    return common;
  }

  public TransactionManager getTransactionManager() {
    return transactionManager;
  }

  public UpdateManager getUpdateManager() {
    return updateManager;
  }

  public LogManager getLogManager() {
    return logManager;
  }

  public SchemaManager getSchemaManager() {
    return schemaManager;
  }

  public PartitionManager getPartitionManager() {
    return partitionManager;
  }

  public void enableSnapshot(boolean enable) {
    snapshotManager.enableSnapshot(enable);
  }

  public void runSnapshot() throws InterruptedException, ParseException, IOException {
    for (String dbName : getDbNames(dataDir)) {
      snapshotManager.runSnapshot(dbName);
    }
    getCommon().saveSchema(getClient(), getDataDir());

  }

  public void recoverFromSnapshot() throws Exception {
    common.loadSchema(dataDir);
    Set<String> dbNames = new HashSet<>();
    for (String dbName : common.getDatabases().keySet()) {
      dbNames.add(dbName);
    }
    for (String dbName : getDbNames(dataDir)) {
      dbNames.add(dbName);
    }
    for (String dbName : dbNames) {
      snapshotManager.recoverFromSnapshot(dbName);
    }
  }

  public void purgeMemory() {
    for (String dbName : indexes.keySet()) {
      for (ConcurrentHashMap<String, Index> index : indexes.get(dbName).getIndices().values()) {
        for (Index innerIndex : index.values()) {
          innerIndex.clear();
        }
      }
    }
  }

  public void replayLogs() {
    logManager.replayLogs();
  }

  public String getCluster() {
    return cluster;
  }

  public void setShardCount(int shardCount) {
    this.shardCount = shardCount;
  }

  public void truncateTablesQuietly() {
    for (Indices indices : indexes.values()) {
      for (ConcurrentHashMap<String, Index> innerIndices : indices.getIndices().values()) {
        for (Index index : innerIndices.values()) {
          index.clear();
        }
      }
    }
  }

  public void setThrottleInsert(boolean throttle) {
    this.throttleInsert = throttle;
  }

  public boolean isThrottleInsert() {
    return throttleInsert;
  }

  public DeleteManager getDeleteManager() {
    return deleteManager;
  }

  public ReentrantReadWriteLock.ReadLock getBatchReadLock() {
    return batchReadLock;
  }

  public ReentrantReadWriteLock.WriteLock getBatchWriteLock() {
    return batchWriteLock;
  }


  private ReentrantReadWriteLock throttleLock = new ReentrantReadWriteLock();
  private Lock throttleWriteLock = throttleLock.writeLock();
  private Lock throttleReadLock = throttleLock.readLock();

  public Lock getThrottleWriteLock() {
    return throttleWriteLock;
  }

  public Lock getThrottleReadLock() {
    return throttleReadLock;
  }

  public AtomicInteger getBatchRepartCount() {
    return batchRepartCount;
  }

  public void overrideProLicense() {
    licenseManager.setOverrideProLicense(true);
  }

  private static String OS = System.getProperty("os.name").toLowerCase();

  public static boolean isWindows() {
    return OS.contains("win");
  }

  public static boolean isMac() {
    return OS.contains("mac");
  }

  public static boolean isUnix() {
    return OS.contains("nux");
  }

  public boolean isRunning() {
    return isRunning.get();
  }

  public boolean isRecovered() {
    return isRecovered.get();
  }

  public LongRunningCalls getLongRunningCommands() {
    return longRunningCommands;
  }

  public ComObject areAllLongRunningCommandsComplete(ComObject cobj, boolean replayedCommand) {
    ComObject retObj = new ComObject();
    if (longRunningCommands.getCommandCount() == 0) {
      retObj.put(ComObject.Tag.isComplete, true);
    }
    else {
      retObj.put(ComObject.Tag.isComplete, false);
    }
    return retObj;
  }

  private void startLongRunningCommands() {
    longRunningCommands.load();

    longRunningCommands.execute();
  }

  private static String algorithm = "DESede";

  public static String createLicense(int serverCount) {
    try {
      SecretKey symKey = new SecretKeySpec(com.sun.jersey.core.util.Base64.decode(DatabaseServer.LICENSE_KEY), algorithm);
      Cipher c = Cipher.getInstance(algorithm);
      byte[] bytes = encryptF("sonicbase:pro:" + serverCount, symKey, c);
      return Hex.encodeHexString(bytes);
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public static void validateLicense(ObjectNode config) {

    try {
      int licensedServerCount = 0;
      int actualServerCount = 0;
      boolean pro = false;
      ArrayNode keys = config.withArray("licenseKeys");
      if (keys == null || keys.size() == 0) {
        pro = false;
      }
      else {
        for (int i = 0; i < keys.size(); i++) {
          String key = keys.get(i).asText();
          SecretKey symKey = new SecretKeySpec(com.sun.jersey.core.util.Base64.decode(DatabaseServer.LICENSE_KEY), algorithm);

          Cipher c = Cipher.getInstance(algorithm);

          String decrypted = null;
          try {
            decrypted = decryptF(Hex.decodeHex(key.toCharArray()), symKey, c);
          }
          catch (Exception e) {
            throw new DatabaseException("Invalid license key");
          }
          decrypted = decrypted.toLowerCase();
          String[] parts = decrypted.split(":");
          if (!parts[0].equals("sonicbase")) {
            throw new DatabaseException("Invalid license key");
          }
          if (parts[1].equals("pro")) {
            licensedServerCount += Integer.valueOf(parts[2]);
            pro = true;
          }
          else {
            pro = false;
          }
        }
      }

      ArrayNode shards = config.withArray("shards");
      for (int i = 0; i < shards.size(); i++) {
        ObjectNode shard = (ObjectNode) shards.get(i);
        ArrayNode replicas = shard.withArray("replicas");
        if (replicas.size() > 1 && !pro) {
          throw new DatabaseException("Replicas are only supported with 'pro' license");
        }
        actualServerCount += replicas.size();
      }
      if (pro) {
        if (actualServerCount > licensedServerCount) {
          throw new DatabaseException("Not enough licensed servers: licensedCount=" + licensedServerCount + ", actualCount=" + actualServerCount);
        }
      }
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  private static byte[] encryptF(String input, Key pkey, Cipher c) throws
      InvalidKeyException, BadPaddingException, IllegalBlockSizeException {

    c.init(Cipher.ENCRYPT_MODE, pkey);

    byte[] inputBytes = input.getBytes();
    return c.doFinal(inputBytes);
  }

  private static String decryptF(byte[] encryptionBytes, Key pkey, Cipher c) throws InvalidKeyException,
      BadPaddingException, IllegalBlockSizeException {
    c.init(Cipher.DECRYPT_MODE, pkey);
    byte[] decrypt = c.doFinal(encryptionBytes);
    String decrypted = new String(decrypt);

    return decrypted;
  }


  public ComObject getDbNames(ComObject cobj, boolean replayedCommand) {

    try {
      ComObject retObj = new ComObject();
      List<String> dbNames = getDbNames(getDataDir());
      ComArray array = retObj.putArray(ComObject.Tag.dbNames, ComObject.Type.stringType);
      for (String dbName : dbNames) {
        array.add(dbName);
      }
      return retObj;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public ComObject updateSchema(ComObject cobj, boolean replayedCommand) throws IOException {
    if (replayedCommand) {
      return null;
    }
    DatabaseCommon tempCommon = new DatabaseCommon();
    tempCommon.deserializeSchema(cobj.getByteArray(ComObject.Tag.schemaBytes));

    synchronized (common) {
      if (tempCommon.getSchemaVersion() > common.getSchemaVersion()) {
        common.deserializeSchema(cobj.getByteArray(ComObject.Tag.schemaBytes));
        common.saveSchema(getClient(), getDataDir());
      }
    }
    return null;
  }



  public ComObject logError(ComObject cobj, boolean replayedCommand) {
    try {
      boolean isClient = cobj.getBoolean(ComObject.Tag.isClient);
      String hostName = cobj.getString(ComObject.Tag.host);
      String msg = cobj.getString(ComObject.Tag.message);
      String exception = cobj.getString(ComObject.Tag.exception);

      StringBuilder actualMsg = new StringBuilder();
      actualMsg.append("host=").append(hostName).append("\n");
      actualMsg.append("msg=").append(msg).append("\n");
      if (exception != null) {
        actualMsg.append("exception=").append(exception);
      }

      if (isClient) {
        getClientErrorLogger().error(actualMsg.toString());
      }
      else {
        getErrorLogger().error(actualMsg.toString());
      }
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
    return null;
  }

  public ComObject getFile(ComObject cobj, boolean replayedCommand) {
    try {
      String filename = cobj.getString(ComObject.Tag.filename);
      File file = new File(getInstallDir(), filename);
      if (!file.exists()) {
        return null;
      }
      try (FileInputStream fileIn = new FileInputStream(file)) {
        String ret = IOUtils.toString(fileIn, "utf-8");
        ComObject retObj = new ComObject();
        retObj.put(ComObject.Tag.fileContent, ret);
        return retObj;
      }
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  public ComObject promoteToMasterAndPushSchema(ComObject cobj, boolean replayedCommand) {
    int shard = cobj.getInt(ComObject.Tag.shard);
    int replica = cobj.getInt(ComObject.Tag.replica);

    logger.info("promoting to master: shard=" + shard + ", replica=" + replica);
    common.getServersConfig().getShards()[shard].setMasterReplica(replica);
    common.saveSchema(getClient(), getDataDir());
    pushSchema();
    return null;
  }

  public ComObject markReplicaAlive(ComObject cobj, boolean replayedCommand) {
    int replicaToMarkAlive = cobj.getInt(ComObject.Tag.replica);
    logger.info("Marking replica alive: replica=" + replicaToMarkAlive);
    for (int shard = 0; shard < getShardCount(); shard++) {
      common.getServersConfig().getShards()[shard].getReplicas()[replicaToMarkAlive].setDead(false);
    }
    common.saveSchema(getClient(), getDataDir());
    pushSchema();

    setReplicaDeadForRestart(-1);
    return null;
  }


  public ComObject markReplicaDead(ComObject cobj, boolean replayedCommand) {
    int replicaToKill = cobj.getInt(ComObject.Tag.replica);
    logger.info("Marking replica dead: replica=" + replicaToKill);
    for (int shard = 0; shard < getShardCount(); shard++) {
      common.getServersConfig().getShards()[shard].getReplicas()[replicaToKill].setDead(true);
    }
    common.saveSchema(getClient(), getDataDir());
    pushSchema();

    setReplicaDeadForRestart(replicaToKill);
    return null;
  }


  private void syncDbNames() {
    logger.info("Syncing database names: shard=" + shard + ", replica=" + replica);
    ComObject cobj = new ComObject();
    cobj.put(ComObject.Tag.dbName, "__none__");
    cobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
    cobj.put(ComObject.Tag.method, "DatabaseServer:getDbNames");
    byte[] ret = getDatabaseClient().send(null, 0, 0, cobj, DatabaseClient.Replica.master, true);
    ComObject retObj = new ComObject(ret);
    ComArray array = retObj.getArray(ComObject.Tag.dbNames);
    for (int i = 0; i < array.getArray().size(); i++) {
      String dbName = (String) array.getArray().get(i);
      File file = null;
      if (USE_SNAPSHOT_MGR_OLD) {
        file = new File(dataDir, "snapshot/" + shard + "/" + replica + "/" + dbName);
      }
      else {
        file = new File(dataDir, "delta/" + shard + "/" + replica + "/" + dbName);
      }
      file.mkdirs();
      logger.info("Received database name: name=" + dbName);
    }
  }


  public List<String> getDbNames(String dataDir) {
    return common.getDbNames(dataDir);
  }

  public void startRepartitioner() {
    logger.info("startRepartitioner - begin");
    if (!partitionManager.isRunning()) {
      partitionManager.start();
    }
    logger.info("startRepartitioner - end");
  }

  public int getReplica() {
    return replica;
  }

  private void initServersForUnitTest(
      String host, int port, boolean unitTest, ServersConfig serversConfig) {
    if (unitTest) {
      int thisShard = serversConfig.getThisShard(host, port);
      int thisReplica = serversConfig.getThisReplica(host, port);
      Map<Integer, Object> currShard = DatabaseClient.dbservers.get(thisShard);
      if (currShard == null) {
        currShard = new ConcurrentHashMap<>();
        DatabaseClient.dbservers.put(thisShard, currShard);
      }
      currShard.put(thisReplica, this);
    }
    int thisShard = serversConfig.getThisShard(host, port);
    int thisReplica = serversConfig.getThisReplica(host, port);
    Map<Integer, Object> currShard = DatabaseClient.dbdebugServers.get(thisShard);
    if (currShard == null) {
      currShard = new ConcurrentHashMap<>();
      DatabaseClient.dbdebugServers.put(thisShard, currShard);
    }
    currShard.put(thisReplica, this);
  }

  public Indices getIndices(String dbName) {
    Indices ret = indexes.get(dbName);
    if (ret == null) {
      ret = new Indices();
      indexes.put(dbName, ret);
    }
    return ret;
  }

  public Map<String, Indices> getIndices() {
    return indexes;
  }

  public DatabaseClient getClient() {
    return getDatabaseClient();
  }

  public int getShard() {
    return shard;
  }

  public int getShardCount() {
    return shardCount;
  }

  public void disableLogProcessor() {
    logManager.enableLogWriter(false);
  }

  public void shutdownRepartitioner() {
    if (partitionManager == null) {
      return;
    }
    logger.info("Shutdown partitionManager - begin");
    partitionManager.shutdown();
    partitionManager.isRebalancing.set(false);
    partitionManager.stopShardsFromRepartitioning();
    partitionManager = new PartitionManager(this, common);
    logger.info("Shutdown partitionManager - end");
  }


  public void pushSchema() {
    try {
      ComObject cobj = new ComObject();
      cobj.put(ComObject.Tag.dbName, "__none__");
      cobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
      cobj.put(ComObject.Tag.method, "DatabaseServer:updateSchema");
      cobj.put(ComObject.Tag.schemaBytes, common.serializeSchema(DatabaseClient.SERIALIZATION_VERSION));

      for (int i = 0; i < shardCount; i++) {
        for (int j = 0; j < replicationFactor; j++) {
          if (shard == 0 && replica == common.getServersConfig().getShards()[0].getMasterReplica()) {
            if (i == shard && j == replica) {
              continue;
            }
            try {
              getDatabaseClient().send(null, i, j, cobj, DatabaseClient.Replica.specified);
            }
            catch (Exception e) {
              logger.error("Error pushing schema to server: shard=" + i + ", replica=" + j);
            }
          }
        }
      }
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public ComObject getDatabaseFile(ComObject cobj, boolean replayedCommand) {
    try {
      String filename = cobj.getString(ComObject.Tag.filename);
      File file = new File(filename);
      BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      IOUtils.copy(in, out);
      out.close();
      byte[] bytes = out.toByteArray();
      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.binaryFileContent, bytes);

      return retObj;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public ComObject healthCheck(ComObject cobj, boolean replayedCommand) {
    ComObject retObj = new ComObject();
    retObj.put(ComObject.Tag.status, "{\"status\" : \"ok\"}");
    retObj.put(ComObject.Tag.haveProLicense, haveProLicense());
    return retObj;
  }

  public ComObject healthCheckPriority(ComObject cobj, boolean replayedCommand) {
    ComObject retObj = new ComObject();
    retObj.put(ComObject.Tag.status, "{\"status\" : \"ok\"}");
    retObj.put(ComObject.Tag.haveProLicense, haveProLicense());
    return retObj;
  }

  public ComObject updateServersConfig(ComObject cobj, boolean replayedCommand) {
    try {
      short serializationVersion = cobj.getShort(ComObject.Tag.serializationVersion);
      ServersConfig serversConfig = new ServersConfig(cobj.getByteArray(ComObject.Tag.serversConfig), serializationVersion);

      common.setServersConfig(serversConfig);
      common.saveServersConfig(getDataDir());
      setShardCount(serversConfig.getShards().length);
      getDatabaseClient().configureServers();
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
    return null;
  }

  public void pushServersConfig() {
    for (int i = 0; i < shardCount; i++) {
      for (int j = 0; j < replicationFactor; j++) {
        if (shard == 0 && replica == common.getServersConfig().getShards()[0].getMasterReplica()) {
          continue;
        }
        try {
          ComObject cobj = new ComObject();
          cobj.put(ComObject.Tag.dbName, "__none__");
          cobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
          cobj.put(ComObject.Tag.method, "DatabaseServer:updateServersConfig");
          cobj.put(ComObject.Tag.serversConfig, common.getServersConfig().serialize(DatabaseClient.SERIALIZATION_VERSION));
          getDatabaseClient().send(null, i, j, cobj, DatabaseClient.Replica.specified);
        }
        catch (Exception e) {
          logger.error("Error pushing servers config: shard=" + i + ", replica=" + j);
        }
      }
    }
  }

  public String getDataDir() {
    return dataDir;
  }

  public void setRole(String role) {
    this.role = DatabaseClient.Replica.primary;
  }

  public ObjectNode getConfig() {
    return config;
  }

  public DatabaseClient.Replica getRole() {
    return role;
  }

  private boolean shutdown = false;

  public static long TIME_2017 = new Date(2017 - 1900, 0, 1, 0, 0, 0).getTime();

  public long getUpdateTime(Object value) {
    try {
      if (value instanceof Long) {
        return addressMap.getUpdateTime((Long) value);
      }
      else {
        return ((AddressMap.IndexValue)value).getUpdateTime();
      }
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public AddressMap getAddressMap() {
    return addressMap;
  }

  public byte[] invokeMethod(final byte[] body, boolean replayedCommand, boolean enableQueuing) {
    return invokeMethod(body, -1L, (short) -1L, replayedCommand, enableQueuing, null, null);
  }

  public byte[] invokeMethod(final byte[] body, long logSequence0, long logSequence1,
                             boolean replayedCommand, boolean enableQueuing, AtomicLong timeLogging, AtomicLong handlerTime) {
    if (methodInvoker == null) {
      throw new DeadServerException("Server not running");
    }
    return methodInvoker.invokeMethod(body, logSequence0, logSequence1, replayedCommand, enableQueuing, timeLogging, handlerTime);
  }


  public void purge(String dbName) {
    if (null != getIndices(dbName) && null != getIndices(dbName).getIndices()) {
      for (Map.Entry<String, ConcurrentHashMap<String, Index>> table : getIndices(dbName).getIndices().entrySet()) {
        for (Map.Entry<String, Index> indexEntry : table.getValue().entrySet()) {
          indexEntry.getValue().clear();
        }
      }
    }
  }

  public ComObject prepareToComeAlive(ComObject cobj, boolean replayedCommand) {
    String slicePoint = null;
    try {
      ComObject pcobj = new ComObject();
      pcobj.put(ComObject.Tag.dbName, "__none__");
      pcobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
      pcobj.put(ComObject.Tag.method, "LogManager:pushMaxSequenceNum");
      getClient().send(null, shard, 0, pcobj, DatabaseClient.Replica.master,
          true);

      if (shard == 0) {
        pcobj = new ComObject();
        pcobj.put(ComObject.Tag.dbName, "__none__");
        pcobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
        pcobj.put(ComObject.Tag.method, "DatabaseServer:pushMaxRecordId");
        getClient().send(null, shard, 0, pcobj, DatabaseClient.Replica.master,
            true);
      }

      for (int replica = 0; replica < replicationFactor; replica++) {
        if (replica != this.replica) {
          logManager.getLogsFromPeer(replica);
        }
      }
      onlyQueueCommands = true;
      slicePoint = logManager.sliceLogs(false);
      logManager.applyLogsFromPeers(slicePoint);
    }
    finally {
      onlyQueueCommands = false;
    }
    try {
      applyingQueuesAndInteractive = true;
      logManager.applyLogsAfterSlice(slicePoint);
    }
    finally {
      applyingQueuesAndInteractive = false;
    }

    return null;
  }

  public ComObject getSchema(ComObject cobj, boolean replayedCommand) {
    short serializationVersionNumber = cobj.getShort(ComObject.Tag.serializationVersion);
    try {

      if (cobj.getBoolean(ComObject.Tag.force) != null && cobj.getBoolean(ComObject.Tag.force)) {
        final ComObject cobj2 = new ComObject();
        cobj2.put(ComObject.Tag.dbName, "__none__");
        cobj2.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
        cobj2.put(ComObject.Tag.method, "DatabaseServer:getSchema");

        int threadCount = getShardCount() * getReplicationFactor();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threadCount, threadCount, 10_000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
          logger.info("forcing schema sync: version=" + common.getSchemaVersion());
          List<Future> futures = new ArrayList<>();
          for (int i = 0; i < getShardCount(); i++) {
            for (int j = 0; j < getReplicationFactor(); j++) {
              try {
                if (common.getServersConfig().getShards()[i].getReplicas()[j].isDead()) {
                  continue;
                }
                if (i == getShard() && j == getReplica()) {
                  continue;
                }

                final int shard = i;
                final int replica = j;

                futures.add(executor.submit(new Callable(){
                  @Override
                  public Object call() throws Exception {
                    byte[] bytes = getClient().send(null, shard, replica, cobj2, DatabaseClient.Replica.specified);
                    ComObject retObj = new ComObject(bytes);
                    DatabaseCommon tmpCommon = new DatabaseCommon();
                    tmpCommon.deserializeSchema(retObj.getByteArray(ComObject.Tag.schemaBytes));
                    synchronized (common) {
                      if (tmpCommon.getSchemaVersion() > common.getSchemaVersion()) {
                        logger.info("Found schema with higher version: version=" + tmpCommon.getSchemaVersion() +
                            ", currVersion=" + common.getSchemaVersion() + ", shard=" + shard + ", replica=" + replica);
                        common.deserializeSchema(retObj.getByteArray(ComObject.Tag.schemaBytes));
                      }
                    }
                    return null;
                  }
                }));
              }
              catch (Exception e) {
                logger.error("Error getting schema: shard=" + i + ", replica=" + j, e);
              }
            }
          }
          for (Future future : futures) {
            future.get();
          }
          pushSchema();
        }
        catch (Exception e) {
          logger.error("Error pushing schema", e);
        }
        finally {
          executor.shutdownNow();
        }
      }
      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.schemaBytes, common.serializeSchema(serializationVersionNumber));
      return retObj;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }



  public ComObject getConfig(ComObject cobj, boolean replayedCommand) {
    short serializationVersionNumber = cobj.getShort(ComObject.Tag.serializationVersion);
    try {
      byte[] bytes = common.serializeConfig(serializationVersionNumber);
      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.configBytes, bytes);
      return retObj;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  public ComObject reconfigureCluster(ComObject cobj, boolean replayedCommand) {
    ServersConfig oldConfig = common.getServersConfig();
    try {
      File file = new File(System.getProperty("user.dir"), "config/config-" + getCluster() + ".json");
      if (!file.exists()) {
        file = new File(System.getProperty("user.dir"), "db/src/main/resources/config/config-" + getCluster() + ".json");
      }
      String configStr = IOUtils.toString(new BufferedInputStream(new FileInputStream(file)), "utf-8");
      logger.info("Config: " + configStr);
      ObjectMapper mapper = new ObjectMapper();
      ObjectNode config = (ObjectNode) mapper.readTree(configStr);

      //validateLicense(config);

      boolean isInternal = false;
      if (config.has("clientIsPrivate")) {
        isInternal = config.get("clientIsPrivate").asBoolean();
      }

      boolean optimizedForThroughput = true;
      if (config.has("optimizeReadsFor")) {
        String text = config.get("optimizeReadsFor").asText();
        if (!text.equalsIgnoreCase("totalThroughput")) {
          optimizedForThroughput = false;
        }
      }

      ServersConfig newConfig = new ServersConfig(cluster, config.withArray("shards"),
          config.withArray("shards").get(0).withArray("replicas").size(), isInternal, optimizedForThroughput);

      common.setServersConfig(newConfig);

      common.saveSchema(getClient(), getDataDir());

      pushSchema();

      ServersConfig.Shard[] oldShards = oldConfig.getShards();
      ServersConfig.Shard[] newShards = newConfig.getShards();

      int count = newShards.length - oldShards.length;
      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.count, count);

      return retObj;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }


  public ComObject reserveNextIdFromReplica(ComObject cobj, boolean replayedCommand) {
    String dbName = cobj.getString(ComObject.Tag.dbName);
    common.getSchemaReadLock(dbName).lock();
    try {
      int schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
      if (schemaVersion < getSchemaVersion()) {
        throw new SchemaOutOfSyncException("currVer:" + common.getSchemaVersion() + ":");
      }
      long highestId = cobj.getLong(ComObject.Tag.highestId);
      long id = -1;
      synchronized (nextRecordId) {
        if (highestId > nextRecordId.get()) {
          nextRecordId.set(highestId);
        }
        id = nextRecordId.getAndIncrement();
      }
      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.highestId, id);
      return retObj;
    }
    finally {
      common.getSchemaReadLock(dbName).unlock();
    }
  }

  private final Object nextIdLock = new Object();

  public ComObject allocateRecordIds(ComObject cobj, boolean replayedCommand) {
    String dbName = cobj.getString(ComObject.Tag.dbName);
    common.getSchemaReadLock(dbName).lock();
    try {
      logger.info("Requesting next record id - begin");
//      int schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
//      if (schemaVersion < getSchemaVersion()) {
//        throw new SchemaOutOfSyncException("currVer:" + common.getSchemaVersion() + ":");
//      }
      long nextId;
      long maxId;
      synchronized (nextIdLock) {
        File file = new File(dataDir, "nextRecordId/" + getShard() + "/" + getReplica() + "/nextRecorId.txt");
        file.getParentFile().mkdirs();
        if (!file.exists()) {
          nextId = 1;
          maxId = 100000;
        }
        else {
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            maxId = Long.valueOf(reader.readLine());
            nextId = maxId + 1;
            maxId += 100000;
          }
          file.delete();
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
          writer.write(String.valueOf(maxId));
        }
      }

      pushMaxRecordId(dbName, maxId);

      logger.info("Requesting next record id - finished: nextId=" + nextId + ", maxId=" + maxId);

      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.nextId, nextId);
      retObj.put(ComObject.Tag.maxId, maxId);
      return retObj;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
    finally {
      common.getSchemaReadLock(dbName).unlock();
    }
  }

  public ComObject pushMaxRecordId(ComObject cobj, boolean replayedCommand) {
    try {
      synchronized (nextIdLock) {
        File file = new File(dataDir, "nextRecordId/" + getShard() + "/" + getReplica() + "/nextRecorId.txt");
        file.getParentFile().mkdirs();
        if (file.exists()) {
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            long maxId = Long.valueOf(reader.readLine());
            pushMaxRecordId("__none__", maxId);
          }
        }
      }
      return null;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  private void pushMaxRecordId(String dbName, long maxId) {
    ComObject cobj = new ComObject();
    cobj.put(ComObject.Tag.dbName, dbName);
    cobj.put(ComObject.Tag.schemaVersion, common.getSchemaVersion());
    cobj.put(ComObject.Tag.method, "DatabaseServer:setMaxRecordId");
    cobj.put(ComObject.Tag.maxId, maxId);

    for (int replica = 0; replica < replicationFactor; replica++) {
      if (replica == this.replica) {
        continue;
      }
      try {
        getDatabaseClient().send(null, 0, replica, cobj, DatabaseClient.Replica.specified, true);
      }
      catch (Exception e) {
        logger.error("Error pushing maxRecordId: replica=" + replica, e);
      }
    }
  }

  public ComObject setMaxRecordId(ComObject cobj, boolean replayedCommand) {
    if (shard == 0 && replica == common.getServersConfig().getShards()[0].getMasterReplica()) {
      return null;
    }
    Long maxId = cobj.getLong(ComObject.Tag.maxId);
    try {
      logger.info("setMaxRecordId - begin");
      synchronized (nextIdLock) {
        File file = new File(dataDir, "nextRecordId/" + getShard() + "/" + getReplica() + "/nextRecorId.txt");
        file.getParentFile().mkdirs();
        file.delete();

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
          writer.write(String.valueOf(maxId));
        }
      }

      logger.info("setMaxRecordId - end");

      return null;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }
}
