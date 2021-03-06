package com.sonicbase.client;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Snapshot;
import com.sonicbase.common.ComArray;
import com.sonicbase.common.ComObject;
import com.sonicbase.common.ServersConfig;
import com.sonicbase.common.ThreadUtil;
import com.sonicbase.query.DatabaseException;
import com.sonicbase.util.Varint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"squid:S1168", "squid:S00107"})
// I prefer to return null instead of an empty array
// I don't know a good way to reduce the parameter count
public class ClientStatsHandler {
  private static final Logger logger = LoggerFactory.getLogger(ClientStatsHandler.class);

  private static final ConcurrentHashMap<String, ConcurrentHashMap<String, HistogramEntry>> registeredQueries = new ConcurrentHashMap<>();
  private final Thread statsEnabler;
  private boolean disableStats;

  public ClientStatsHandler() {
    this.statsEnabler = ThreadUtil.createThread(() -> {
      while (true) {
        try {
          Thread.sleep(60_000);
          disableStats = false;
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }, "SonicBase Stats Enabler");
  }

  public void shutdown() {
    this.statsEnabler.interrupt();
    try {
      this.statsEnabler.join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DatabaseException(e);
    }
  }

  public void registerCompletedQueryForStats(HistogramEntry histogramEntry, long beginNanos) {
    if (disableStats) {
      return;
    }
    long latency = System.nanoTime() - beginNanos;
    histogramEntry.getHistogram().update(latency);
    if (histogramEntry.getMaxedLatencies().get()) {
      return;
    }
    histogramEntry.getLatencies().add(latency);
    if (histogramEntry.getLatencies().size() > 1_000) {
      histogramEntry.getMaxedLatencies().set(true);
      histogramEntry.getLatencies().clear();
    }
  }

  public static class QueryStatsRecorder implements Runnable {

    private final String cluster;
    private final DatabaseClient client;
    private final Long sleepOverride;
    private AtomicBoolean shutdownStatsRecorderThreads;

    public QueryStatsRecorder(DatabaseClient client, String cluster, AtomicBoolean shutdownStatsRecorderThreads) {
      this.client = client;
      this.cluster = cluster;
      sleepOverride = null;
      this.shutdownStatsRecorderThreads = shutdownStatsRecorderThreads;
    }

    public QueryStatsRecorder(DatabaseClient client, String cluster, long sleepOverride) {
      this.client = client;
      this.cluster = cluster;
      this.sleepOverride = sleepOverride;
    }

    @Override
    public void run() {
      while (!shutdownStatsRecorderThreads.get()) {
        try {
          doRecordStats();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        catch (Exception e) {
          logger.error("Error in stats reporting thread", e);
        }
      }
    }

    private void doRecordStats() throws InterruptedException, IOException {
      Thread.sleep(sleepOverride == null ? 15_000 : sleepOverride);

      boolean oneIsAlive = false;
      DatabaseClient sharedClient = DatabaseClient.getSharedClients().get(cluster);
      ServersConfig.Host[] replicas = sharedClient.getCommon().getServersConfig().getShards()[0].getReplicas();
      oneIsAlive = checkIfAtLeastOneHostIsAlive(oneIsAlive, replicas);

      if (!oneIsAlive) {
        sharedClient.syncSchema();
        return;
      }

      if (registeredQueries.get(cluster) == null) {
        return;
      }
      ComObject cobj = new ComObject(1);
      ComArray array = cobj.putArray(ComObject.Tag.HISTOGRAM_SNAPSHOT, ComObject.Type.OBJECT_TYPE, registeredQueries.get(cluster).size());

      for (HistogramEntry entry : registeredQueries.get(cluster).values()) {
        if (entry.getHistogram() == null || entry.getHistogram().getCount() == 0) {
          continue;
        }

        addSnapshotObj(array, entry);

        entry.getLatencies().clear();
        entry.getMaxedLatencies().set(false);
        entry.setHistogram(null);
      }

      sharedClient.send("MonitorManager:registerStats", 0, 0, cobj, DatabaseClient.Replica.DEF);
    }

    private boolean checkIfAtLeastOneHostIsAlive(boolean oneIsAlive, ServersConfig.Host[] replicas) {
      for (ServersConfig.Host host : replicas) {
        if (!host.isDead()) {
          oneIsAlive = true;
          break;
        }
      }
      return oneIsAlive;
    }

    private void addSnapshotObj(ComArray array, HistogramEntry entry) throws IOException {
      ComObject snapshotObj = new ComObject(10);
      snapshotObj.put(ComObject.Tag.DB_NAME, entry.getDbName());
      snapshotObj.put(ComObject.Tag.ID, entry.getQueryId());
      if (!entry.getMaxedLatencies().get()) {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytesOut);
        for (Long latency : entry.getLatencies()) {
          Varint.writeUnsignedVarLong(latency, out);
        }
        snapshotObj.put(ComObject.Tag.LATENCIES_BYTES, bytesOut.toByteArray());
        array.add(snapshotObj);
      }
      else {
        Snapshot snapshot = entry.getHistogram().getSnapshot();
        snapshotObj.put(ComObject.Tag.COUNT, (int) entry.getHistogram().getCount());
        snapshotObj.put(ComObject.Tag.LAT_AVG, (double) snapshot.getMean());
        snapshotObj.put(ComObject.Tag.LAT_75, (double) snapshot.get75thPercentile());
        snapshotObj.put(ComObject.Tag.LAT_95, (double) snapshot.get95thPercentile());
        snapshotObj.put(ComObject.Tag.LAT_99, (double) snapshot.get99thPercentile());
        snapshotObj.put(ComObject.Tag.LAT_999, (double) snapshot.get999thPercentile());
        snapshotObj.put(ComObject.Tag.LAT_MAX, (double) snapshot.getMax());
        array.add(snapshotObj);
      }
    }
  }

  public static class HistogramEntry {
    private Histogram histogram;
    private long queryId;
    private String dbName;
    private String query;
    private AtomicBoolean maxedLatencies = new AtomicBoolean();
    private ConcurrentLinkedQueue<Long> latencies;

    Histogram getHistogram() {
      return histogram;
    }

    void setHistogram(Histogram histogram) {
      this.histogram = histogram;
    }

    long getQueryId() {
      return queryId;
    }

    void setQueryId(long queryId) {
      this.queryId = queryId;
    }

    public String getDbName() {
      return dbName;
    }

    public void setDbName(String dbName) {
      this.dbName = dbName;
    }

    public String getQuery() {
      return query;
    }

    public void setQuery(String query) {
      this.query = query;
    }

    AtomicBoolean getMaxedLatencies() {
      return maxedLatencies;
    }

    void setMaxedLatencies(AtomicBoolean maxedLatencies) {
      this.maxedLatencies = maxedLatencies;
    }

    ConcurrentLinkedQueue<Long> getLatencies() {
      return latencies;
    }

    void setLatencies(ConcurrentLinkedQueue<Long> latencies) {
      this.latencies = latencies;
    }
  }

  public HistogramEntry registerQueryForStats(String cluster, String dbName, String sql) {
    try {
      if (this.disableStats) {
        return null;
      }
      ConcurrentHashMap<String, HistogramEntry> clusterEntry = registeredQueries.get(cluster);
      if (clusterEntry == null) {
        clusterEntry = new ConcurrentHashMap<>();
        registeredQueries.put(cluster, clusterEntry);
      }
      HistogramEntry entry = clusterEntry.get(sql);
      if (entry != null) {
        //make a copy because background thread may wipe out the histogram
        HistogramEntry retEntry = new HistogramEntry();
        retEntry.setQueryId(entry.getQueryId());
        retEntry.setQuery(entry.getQuery());
        retEntry.setDbName(entry.getDbName());
        retEntry.setHistogram(entry.getHistogram());
        retEntry.setLatencies(entry.getLatencies());
        retEntry.setMaxedLatencies(entry.getMaxedLatencies());
        if (retEntry.getHistogram() == null) {
          Histogram histogram = new Histogram(new ExponentiallyDecayingReservoir());
          entry.setHistogram(histogram);
          retEntry.setHistogram(histogram);
        }
        return retEntry;
      }

      ComObject cobj = new ComObject(3);
      cobj.put(ComObject.Tag.METHOD, "MonitorManager:registerQueryForStats");
      cobj.put(ComObject.Tag.DB_NAME, dbName);
      cobj.put(ComObject.Tag.SQL, sql);

      DatabaseClient sharedClient = DatabaseClient.getSharedClients().get(cluster);
      byte[] ret = sendToMasterOnSharedClient(cobj, sharedClient);
      if (ret == null) {
        disableStats = true;
      }
      else {
        ComObject retObj = new ComObject(ret);
        entry = new HistogramEntry();
        HistogramEntry retEntry = new HistogramEntry();
        entry.setQueryId(retObj.getLong(ComObject.Tag.ID));
        retEntry.setQueryId(retObj.getLong(ComObject.Tag.ID));
        entry.setDbName(dbName);
        retEntry.setDbName(dbName);
        Histogram histogram = new Histogram(new ExponentiallyDecayingReservoir());
        entry.setHistogram(histogram);
        retEntry.setHistogram(histogram);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        entry.setLatencies(latencies);
        retEntry.setLatencies(latencies);
        retEntry.setMaxedLatencies(entry.getMaxedLatencies());
        entry.setQuery(sql);
        retEntry.setQuery(sql);
        clusterEntry.put(sql, entry);
        return retEntry;
      }
    }
    catch (Exception e) {
      logger.error("Error registering completed query", e);
    }
    return null;
  }

  public byte[] sendToMasterOnSharedClient(ComObject cobj, DatabaseClient sharedClient) {
    return sharedClient.sendToMaster(cobj);
  }

}
