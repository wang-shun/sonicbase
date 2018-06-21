package com.sonicbase.server;

import com.sonicbase.client.DatabaseClient;
import com.sonicbase.common.*;
import com.sonicbase.index.Index;
import com.sonicbase.jdbcdriver.ParameterHandler;
import com.sonicbase.procedure.StoredProcedureContextImpl;
import com.sonicbase.query.BinaryExpression;
import com.sonicbase.query.DatabaseException;
import com.sonicbase.query.Expression;
import com.sonicbase.query.impl.*;
import com.sonicbase.schema.DataType;
import com.sonicbase.schema.FieldSchema;
import com.sonicbase.schema.IndexSchema;
import com.sonicbase.schema.TableSchema;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Responsible for
 */
public class ReadManager {

  private Logger logger;

  private final com.sonicbase.server.DatabaseServer server;
  private Thread diskReaper;
  private boolean shutdown;
  private AtomicInteger lookupCount = new AtomicInteger();


  public ReadManager(DatabaseServer databaseServer) {

    this.server = databaseServer;
    this.logger = new Logger(null/*databaseServer.getDatabaseClient()*/);

    startDiskResultsReaper();
  }

  private void startDiskResultsReaper() {
    diskReaper = ThreadUtil.createThread(new Runnable(){
      @Override
      public void run() {
        while (!shutdown) {
          try {
            DiskBasedResultSet.deleteOldResultSets(server);
          }
          catch (Exception e) {
            logger.error("Error in disk results reaper thread", e);
          }
          try {
            Thread.sleep(100 * 1000);
          }
          catch (InterruptedException e) {
            break;
          }
        }
      }
    }, "SonicBase Disk Results Reaper Thread");
    diskReaper.start();
  }


  @SchemaReadLock
  public ComObject countRecords(ComObject cobj, boolean replayedCommand) {
    if (server.getBatchRepartCount().get() != 0 && lookupCount.incrementAndGet() % 1000 == 0) {
      try {
        Thread.sleep(10);
      }
      catch (InterruptedException e) {
        throw new DatabaseException(e);
      }
    }

    String dbName = cobj.getString(ComObject.Tag.dbName);
    Integer schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
    if (schemaVersion != null && schemaVersion < server.getSchemaVersion()) {
      throw new SchemaOutOfSyncException("currVer:" + server.getCommon().getSchemaVersion() + ":");
    }
    String fromTable = cobj.getString(ComObject.Tag.tableName);

    byte[] expressionBytes = cobj.getByteArray(ComObject.Tag.legacyExpression);
    Expression expression = null;
    if (expressionBytes != null) {
      expression = ExpressionImpl.deserializeExpression(expressionBytes);
    }
    byte[] parmsBytes = cobj.getByteArray(ComObject.Tag.parms);
    ParameterHandler parms = null;
    if (parmsBytes != null) {
      parms = new ParameterHandler();
      parms.deserialize(parmsBytes);
    }
    String countColumn = cobj.getString(ComObject.Tag.countColumn);

    long count = 0;
    String primaryKeyIndex = null;
    for (Map.Entry<String, IndexSchema> entry : server.getCommon().getTableSchema(dbName, fromTable, server.getDataDir()).getIndexes().entrySet()) {
      if (entry.getValue().isPrimaryKey()) {
        primaryKeyIndex = entry.getValue().getName();
        break;
      }
    }
    TableSchema tableSchema = server.getCommon().getTableSchema(dbName, fromTable, server.getDataDir());
    Index index = server.getIndex(dbName, fromTable, primaryKeyIndex);

    int countColumnOffset = 0;
    if (countColumn != null) {
      for (int i = 0; i < tableSchema.getFields().size(); i++) {
        FieldSchema field = tableSchema.getFields().get(i);
        if (field.getName().equals(countColumn)) {
          countColumnOffset = i;
          break;
        }
      }
    }

    if (countColumn == null && expression == null) {
      count = index.getCount();
    }
    else {
      Map.Entry<Object[], Object> entry = index.firstEntry();
      while (true) {
        if (entry == null) {
          break;
        }
        byte[][] records = null;
        if (entry.getValue() != null && !entry.getValue().equals(0L)) {
          records = server.getAddressMap().fromUnsafeToRecords(entry.getValue());
        }
        for (byte[] bytes : records) {
          if ((Record.getDbViewFlags(bytes) & Record.DB_VIEW_FLAG_DELETING) != 0) {
            continue;
          }
          Record record = new Record(tableSchema);
          record.deserialize(dbName, server.getCommon(), bytes, null, true);
          boolean pass = true;
          if (countColumn != null) {
            if (record.getFields()[countColumnOffset] == null) {
              pass = false;
            }
          }
          if (pass) {
            if (expression == null) {
              count++;
            }
            else {
              pass = (Boolean) ((ExpressionImpl) expression).evaluateSingleRecord(new TableSchema[]{tableSchema}, new Record[]{record}, parms);
              if (pass) {
                count++;
              }
            }
          }
        }
        entry = index.higherEntry(entry.getKey());
      }
    }


    ComObject retObj = new ComObject();
    retObj.put(ComObject.Tag.countLong, count);
    return retObj;
  }

  @SchemaReadLock
  public ComObject batchIndexLookup(ComObject cobj, boolean replayedCommand) {
    try {
      if (server.getBatchRepartCount().get() != 0 && lookupCount.incrementAndGet() % 1000 == 0) {
        try {
          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new DatabaseException(e);
        }
      }

      IndexLookup indexLookup = new IndexLookupOneKey(server);
      String dbName = cobj.getString(ComObject.Tag.dbName);
      indexLookup.setDbName(dbName);
      Integer schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
      if (schemaVersion != null && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException("currVer:" + server.getCommon().getSchemaVersion() + ":");
      }
      indexLookup.setCount(cobj.getInt(ComObject.Tag.count));
      indexLookup.setSerializationVersion(cobj.getShort(ComObject.Tag.serializationVersion));

      String tableName = cobj.getString(ComObject.Tag.tableName);
      String indexName = cobj.getString(ComObject.Tag.indexName);
      indexLookup.setTableName(tableName);
      indexLookup.setIndexName(indexName);
      TableSchema tableSchema = server.getCommon().getTableSchema(dbName, tableName, server.getDataDir());
      IndexSchema indexSchema = server.getIndexSchema(dbName, tableSchema.getName(), indexName);
      indexLookup.setTableSchema(tableSchema);
      indexLookup.setIndexSchema(indexSchema);
      Index index = server.getIndex(dbName, tableSchema.getName(), indexName);
      indexLookup.setIndex(index);

      indexLookup.setCurrOffset(new AtomicLong());
      indexLookup.setCountReturned(new AtomicLong());

      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.serializationVersion, DatabaseClient.SERIALIZATION_VERSION);

      int leftOperatorId = cobj.getInt(ComObject.Tag.leftOperator);
      BinaryExpression.Operator leftOperator = BinaryExpression.Operator.getOperator(leftOperatorId);
      indexLookup.setLeftOperator(leftOperator);

      ComArray cOffsets = cobj.getArray(ComObject.Tag.columnOffsets);
      Set<Integer> columnOffsets = new HashSet<>();
      for (Object obj : cOffsets.getArray()) {
        columnOffsets.add((Integer)obj);
      }
      indexLookup.setColumnOffsets(columnOffsets);

      boolean singleValue = cobj.getBoolean(ComObject.Tag.singleValue);

      ComArray keys = cobj.getArray(ComObject.Tag.keys);
      ComArray retKeysArray = retObj.putArray(ComObject.Tag.retKeys, ComObject.Type.objectType);
      for (Object keyObj : keys.getArray()) {
        ComObject key = (ComObject)keyObj;
        int offset = key.getInt(ComObject.Tag.offset);
        Object[] leftKey = null;
        if (singleValue) {
          leftKey = new Object[]{key.getLong(ComObject.Tag.longKey)};
        }
        else {
          byte[] keyBytes = key.getByteArray(ComObject.Tag.keyBytes);
          leftKey = DatabaseCommon.deserializeKey(tableSchema, keyBytes);
        }
        indexLookup.setLeftKey(leftKey);
        indexLookup.setOriginalLeftKey(leftKey);

        List<byte[]> retKeyRecords = new ArrayList<>();
        List<Object[]> retKeys = new ArrayList<>();
        List<byte[]> retRecords = new ArrayList<>();

        indexLookup.setRetKeyRecords(retKeyRecords);
        indexLookup.setRetKeys(retKeys);
        indexLookup.setRetRecords(retRecords);

        boolean returnKeys = true;
        if (indexSchema.isPrimaryKey()) {
          returnKeys = false;
        }
        indexLookup.setKeys(returnKeys);
        indexLookup.lookup();

        ComObject retEntry = new ComObject();
        retKeysArray.add(retEntry);
        retEntry.put(ComObject.Tag.offset, offset);
        retEntry.put(ComObject.Tag.keyCount, retKeyRecords.size());

        ComArray keysArray = retEntry.putArray(ComObject.Tag.keyRecords, ComObject.Type.byteArrayType);
        for (byte[] currKey : retKeyRecords) {
          keysArray.add(currKey);
        }
        keysArray = retEntry.putArray(ComObject.Tag.keys, ComObject.Type.byteArrayType);
        for (Object[] currKey : retKeys) {
          keysArray.add(DatabaseCommon.serializeKey(tableSchema, indexName, currKey));
        }
        ComArray retRecordsArray = retEntry.putArray(ComObject.Tag.records, ComObject.Type.byteArrayType);
        for (int j = 0; j < retRecords.size(); j++) {
          byte[] bytes = retRecords.get(j);
          retRecordsArray.add(bytes);
        }
      }
      return retObj;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public void shutdown() {
    try {
      shutdown = true;
      if (diskReaper != null) {
        diskReaper.interrupt();
        diskReaper.join();
      }
    }
    catch (InterruptedException e) {
      throw new DatabaseException(e);
    }
  }

  @SchemaReadLock
  public ComObject indexLookup(ComObject cobj, boolean replayedCommand) {
    return indexLookup(cobj, null);
  }

  public ComObject indexLookup(ComObject cobj, StoredProcedureContextImpl procedureContext) {
    try {

      if (server.getBatchRepartCount().get() != 0 && lookupCount.incrementAndGet() % 1000 == 0) {
        try {
          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          throw new DatabaseException(e);
        }
      }

      Integer schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
      if (schemaVersion != null && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException("currVer:" + server.getCommon().getSchemaVersion() + ":");
      }
      else if (schemaVersion != null && schemaVersion > server.getSchemaVersion()) {
        if (server.getShard() != 0 || server.getReplica() != 0) {
          server.getDatabaseClient().syncSchema();
          schemaVersion = server.getSchemaVersion();
        }
        logger.error("Client schema is newer than server schema: client=" + schemaVersion + ", server=" + server.getSchemaVersion());
      }

      short serializationVersion = cobj.getShort(ComObject.Tag.serializationVersion);

      IndexLookup indexLookup = null;
      if (cobj.getInt(ComObject.Tag.rightOperator) != null) {
        indexLookup = new IndexLookupTwoKeys(server);
        indexLookup.setRightOperator(BinaryExpression.Operator.getOperator(cobj.getInt(ComObject.Tag.rightOperator)));
      }
      else {
        indexLookup = new IndexLookupOneKey(server);
      }

      indexLookup.setProcedureContext(procedureContext);
      indexLookup.setSerializationVersion(serializationVersion);
      indexLookup.setCount(cobj.getInt(ComObject.Tag.count));
      indexLookup.setIsExplicitTrans(cobj.getBoolean(ComObject.Tag.isExcpliciteTrans));
      indexLookup.setIsCommiting(cobj.getBoolean(ComObject.Tag.isCommitting));
      indexLookup.setTransactionId(cobj.getLong(ComObject.Tag.transactionId));
      indexLookup.setViewVersion(cobj.getLong(ComObject.Tag.viewVersion));
      Boolean isProbe = cobj.getBoolean(ComObject.Tag.isProbe);
      if (isProbe == null) {
        isProbe = false;
      }
      indexLookup.setIsProbe(isProbe);

      int tableId = 0;
      int indexId = 0;

      tableId = cobj.getInt(ComObject.Tag.tableId);
      indexId = cobj.getInt(ComObject.Tag.indexId);
      indexLookup.setForceSelectOnServer(cobj.getBoolean(ComObject.Tag.forceSelectOnServer));

      ParameterHandler parms = null;
      byte[] parmBytes = cobj.getByteArray(ComObject.Tag.parms);
      if (parmBytes != null) {
        parms = new ParameterHandler();
        parms.deserialize(parmBytes);
      }
      indexLookup.setParms(parms);

      indexLookup.setEvaluateExpression(cobj.getBoolean(ComObject.Tag.evaluateExpression));

      Expression expression = null;

      byte[] expressionBytes = cobj.getByteArray(ComObject.Tag.legacyExpression);
      if (expressionBytes != null) {
        expression = ExpressionImpl.deserializeExpression(expressionBytes);
      }
      indexLookup.setExpression(expression);
      String dbName = cobj.getString(ComObject.Tag.dbName);
      indexLookup.setDbName(dbName);
      String tableName = null;
      String indexName = null;
      TableSchema tableSchema = null;
      IndexSchema indexSchema = null;
      try {
        Map<Integer, TableSchema> tablesById = server.getCommon().getTablesById(dbName);
        if (tablesById == null) {
          logger.error("Error");
        }
        tableSchema = tablesById.get(tableId);
        if (tableSchema == null) {
          logger.error("Error");
        }
        tableName = tableSchema.getName();
        indexLookup.setTableName(tableName);
        indexLookup.setTableSchema(tableSchema);
        indexSchema = tableSchema.getIndexesById().get(indexId);
        indexLookup.setIndexSchema(indexSchema);
        indexName = indexSchema.getName();
        indexLookup.setIndexName(indexName);
      }
      catch (Exception e) {
        logger.info("indexLookup: tableName=" + tableName + ", tableid=" + tableId + ", tableByNameCount=" + server.getCommon().getTables(dbName).size() + ", tableCount=" + server.getCommon().getTablesById(dbName).size() +
            ", tableNull=" + (server.getCommon().getTablesById(dbName).get(tableId) == null) + ", indexName=" + indexName + ", indexId=" + indexId +
            ", indexNull=" /*+ (common.getTablesById().get(tableId).getIndexesById().get(indexId) == null) */);
        throw e;
      }
      List<OrderByExpressionImpl> orderByExpressions = null;
      orderByExpressions = new ArrayList<>();
      ComArray oarray = cobj.getArray(ComObject.Tag.orderByExpressions);
      if (oarray != null) {
        for (Object entry : oarray.getArray()) {
          OrderByExpressionImpl orderByExpression = new OrderByExpressionImpl();
          orderByExpression.deserialize((byte[]) entry);
          orderByExpressions.add(orderByExpression);
        }
      }
      indexLookup.setOrderByExpressions(orderByExpressions);

      byte[] leftBytes = cobj.getByteArray(ComObject.Tag.leftKey);
      Object[] leftKey = null;
      if (leftBytes != null) {
        leftKey = DatabaseCommon.deserializeTypedKey(leftBytes);
      }
      indexLookup.setLeftKey(leftKey);
      byte[] originalLeftBytes = cobj.getByteArray(ComObject.Tag.originalLeftKey);
      Object[] originalLeftKey = null;
      if (originalLeftBytes != null) {
        originalLeftKey = DatabaseCommon.deserializeTypedKey(originalLeftBytes);
      }
      indexLookup.setOriginalLeftKey(originalLeftKey);
      indexLookup.setLeftOperator(BinaryExpression.Operator.getOperator(cobj.getInt(ComObject.Tag.leftOperator)));

      byte[] rightBytes = cobj.getByteArray(ComObject.Tag.rightKey);
      byte[] originalRightBytes = cobj.getByteArray(ComObject.Tag.originalRightKey);
      if (rightBytes != null) {
        indexLookup.setRightKey(DatabaseCommon.deserializeTypedKey(rightBytes));
      }
      if (originalRightBytes != null) {
        indexLookup.setOriginalRightKey(DatabaseCommon.deserializeTypedKey(originalRightBytes));
      }

      if (cobj.getInt(ComObject.Tag.rightOperator) != null) {
        indexLookup.setRightOperator(BinaryExpression.Operator.getOperator(cobj.getInt(ComObject.Tag.rightOperator)));
      }

      Set<Integer> columnOffsets = null;
      ComArray cOffsets = cobj.getArray(ComObject.Tag.columnOffsets);
      columnOffsets = new HashSet<>();
      for (Object obj : cOffsets.getArray()) {
        columnOffsets.add((Integer)obj);
      }
      indexLookup.setColumnOffsets(columnOffsets);

      Counter[] counters = null;
      ComArray counterArray = cobj.getArray(ComObject.Tag.counters);
      if (counterArray != null && counterArray.getArray().size() != 0) {
        counters = new Counter[counterArray.getArray().size()];
        for (int i = 0; i < counters.length; i++) {
          counters[i] = new Counter();
          counters[i].deserialize((byte[])counterArray.getArray().get(i));
        }
      }
      indexLookup.setCounters(counters);

      byte[] groupContextBytes = cobj.getByteArray(ComObject.Tag.legacyGroupContext);
      GroupByContext groupContext = null;
      if (groupContextBytes != null) {
        groupContext = new GroupByContext();
        groupContext.deserialize(groupContextBytes, server.getCommon(), dbName);
      }
      indexLookup.setGroupContext(groupContext);

      Long offset = cobj.getLong(ComObject.Tag.offsetLong);
      Long limit = cobj.getLong(ComObject.Tag.limitLong);
      AtomicLong currOffset = new AtomicLong(cobj.getLong(ComObject.Tag.currOffset));
      AtomicLong countReturned = new AtomicLong();
      if (cobj.getLong(ComObject.Tag.countReturned) != null) {
        countReturned.set(cobj.getLong(ComObject.Tag.countReturned));
      }
      indexLookup.setCurrOffset(currOffset);
      indexLookup.setCountReturned(countReturned);
      indexLookup.setOffset(offset);
      indexLookup.setLimit(limit);

      Index index = server.getIndex(dbName, tableSchema.getName(), indexName);
      Map.Entry<Object[], Object> entry = null;
      indexLookup.setIndex(index);

      Boolean ascending = null;
      if (orderByExpressions != null && orderByExpressions.size() != 0) {
        OrderByExpressionImpl orderByExpression = orderByExpressions.get(0);
        String columnName = orderByExpression.getColumnName();
        boolean isAscending = orderByExpression.isAscending();
        if (orderByExpression.getTableName() == null || !orderByExpression.getTableName().equals(tableSchema.getName()) ||
            columnName.equals(indexSchema.getFields()[0])) {
          ascending = isAscending;
        }
      }
      indexLookup.setAscending(ascending);

      List<byte[]> retKeyRecords = new ArrayList<>();
      indexLookup.setRetKeyRecords(retKeyRecords);
      List<Object[]> retKeys = new ArrayList<>();
      indexLookup.setRetKeys(retKeys);
      List<byte[]> retRecords = new ArrayList<>();
      indexLookup.setRetRecords(retRecords);

      List<Object[]> excludeKeys = new ArrayList<>();
      indexLookup.setExcludeKeys(excludeKeys);

      String[] fields = tableSchema.getPrimaryKey();
      int[] keyOffsets = new int[fields.length];
      for (int i = 0; i < keyOffsets.length; i++) {
        keyOffsets[i] = tableSchema.getFieldOffset(fields[i]);
      }
      indexLookup.setKeyOffsets(keyOffsets);

      if (indexLookup.isExplicitTrans() && !indexLookup.isCommitting()) {
        TransactionManager.Transaction trans = server.getTransactionManager().getTransaction(indexLookup.getTransactionId());
        if (trans != null) {
          List<Record> records = trans.getRecords().get(tableName);
          if (records != null) {
            for (Record record : records) {
              boolean pass = (Boolean) ((ExpressionImpl) expression).evaluateSingleRecord(new TableSchema[]{tableSchema}, new Record[]{record}, parms);
              if (pass) {
                Object[] excludeKey = new Object[keyOffsets.length];
                for (int i = 0; i < excludeKey.length; i++) {
                  excludeKey[i] = record.getFields()[keyOffsets[i]];
                }
                excludeKeys.add(excludeKey);
                retRecords.add(record.serialize(server.getCommon(), cobj.getShort(ComObject.Tag.serializationVersion)));
              }
            }
          }
        }
      }

      if (indexSchema.isPrimaryKey()) {
        indexLookup.setKeys(false);
      }
      else {
        indexLookup.setKeys(true);
      }
      entry = indexLookup.lookup();

      ComObject retObj = new ComObject();
      if (entry != null) {
        retObj.put(ComObject.Tag.keyBytes, DatabaseCommon.serializeKey(tableSchema, indexName, entry.getKey()));
      }
      ComArray array = retObj.putArray(ComObject.Tag.keys, ComObject.Type.byteArrayType);
      for (Object[] key : retKeys) {
        array.add(DatabaseCommon.serializeKey(tableSchema, indexName, key));
      }
      array = retObj.putArray(ComObject.Tag.keyRecords, ComObject.Type.byteArrayType);
      for (byte[] key : retKeyRecords) {
        array.add(key);
      }
      array = retObj.putArray(ComObject.Tag.records, ComObject.Type.byteArrayType);
      for (int i = 0; i < retRecords.size(); i++) {
        byte[] bytes = retRecords.get(i);
        array.add(bytes);
      }

      if (counters != null) {
        array = retObj.putArray(ComObject.Tag.counters, ComObject.Type.byteArrayType);
        for (int i = 0; i < counters.length; i++) {
          array.add(counters[i].serialize());
        }
      }

      if (groupContext != null) {
        retObj.put(ComObject.Tag.legacyGroupContext, groupContext.serialize(server.getCommon()));
      }

      retObj.put(ComObject.Tag.currOffset, currOffset.get());
      retObj.put(ComObject.Tag.countReturned, countReturned.get());
      return retObj;
    }
    catch (IOException e) {
      e.printStackTrace();
      throw new DatabaseException(e);
    }
  }

  @SchemaReadLock
  public ComObject closeResultSet(ComObject cobj, boolean replayedCommand) {
    long resultSetId = cobj.getLong(ComObject.Tag.resultSetId);

    DiskBasedResultSet resultSet = new DiskBasedResultSet(server, resultSetId);
    resultSet.delete();

    return null;
  }

  @SchemaReadLock
  public ComObject serverSelectDelete(ComObject cobj, boolean replayedCommand) {
    String dbName = cobj.getString(ComObject.Tag.dbName);
    long id = cobj.getLong(ComObject.Tag.id);

    DiskBasedResultSet resultSet = new DiskBasedResultSet(server, id);
    resultSet.delete();
    return null;
  }

  @SchemaReadLock
  public ComObject serverSelect(ComObject cobj, boolean replayedCommand) {
    return serverSelect(cobj, false,null);
  }

  public ComObject serverSelect(ComObject cobj, boolean restrictToThisServer, StoredProcedureContextImpl procedureContext) {
    try {
      int schemaRetryCount = 0;
      if (server.getBatchRepartCount().get() != 0 && lookupCount.incrementAndGet() % 1000 == 0) {
        try {
          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          throw new DatabaseException(e);
        }
      }

      String dbName = cobj.getString(ComObject.Tag.dbName);
      Integer schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
      if (schemaVersion != null && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException("currVer:" + server.getCommon().getSchemaVersion() + ":");
      }
      int count = cobj.getInt(ComObject.Tag.count);

      byte[] selectBytes = cobj.getByteArray(ComObject.Tag.legacySelectStatement);
      SelectStatementImpl select = new SelectStatementImpl(server.getDatabaseClient());
      select.deserialize(selectBytes, dbName);
      select.setIsOnServer(true);

      select.setServerSelectPageNumber(select.getServerSelectPageNumber() + 1);
      select.setServerSelectShardNumber(server.getShard());
      select.setServerSelectReplicaNumber(server.getReplica());

      Offset offset = select.getOffset();
      select.setOffset(null);
      Limit limit = select.getLimit();
      select.setLimit(null);
      DiskBasedResultSet diskResults = null;
      if (select.getServerSelectPageNumber() == 0) {
        ResultSetImpl resultSet = (ResultSetImpl) select.execute(dbName, null, null, null, null,
            null, restrictToThisServer, procedureContext, schemaRetryCount);

        ExpressionImpl.CachedRecord[][] results = resultSet.getReadRecordsAndSerializedRecords();
        if (results != null) {
          ComObject retObj = processServerSelectResults(cobj, count, select, offset, limit, results);
          if (retObj != null) {
            return retObj;
          }
        }

        diskResults = new DiskBasedResultSet(cobj.getShort(ComObject.Tag.serializationVersion), dbName, server, select.getOffset(), select.getLimit(),
            select.getTableNames(), new int[]{0}, new ResultSetImpl[]{resultSet}, select.getOrderByExpressions(), count, select, false);
      }
      else {
        diskResults = new DiskBasedResultSet(server, select, select.getTableNames(), select.getServerSelectResultSetId(), restrictToThisServer, procedureContext);
      }
      select.setServerSelectResultSetId(diskResults.getResultSetId());
      byte[][][] records = diskResults.nextPage(select.getServerSelectPageNumber());

      ComObject retObj = new ComObject();
      select.setIsOnServer(false);
      retObj.put(ComObject.Tag.legacySelectStatement, select.serialize());

      long currOffset = 0;
      if (cobj.getLong(ComObject.Tag.currOffset) != null) {
        currOffset = cobj.getLong(ComObject.Tag.currOffset);
      }
      long countReturned = 0;
      if (cobj.getLong(ComObject.Tag.countReturned) != null) {
        countReturned = cobj.getLong(ComObject.Tag.countReturned);
      }
      if (records != null) {
        ComArray tableArray = retObj.putArray(ComObject.Tag.tableRecords, ComObject.Type.arrayType);
        outer:
        for (byte[][] tableRecords : records) {
          ComArray recordArray = null;

          for (byte[] record : tableRecords) {
            if (offset != null && currOffset < offset.getOffset()) {
              currOffset++;
              continue;
            }
            if (limit != null && countReturned >= limit.getRowCount()) {
              break outer;
            }
            if (recordArray == null) {
              recordArray = tableArray.addArray(ComObject.Tag.records, ComObject.Type.byteArrayType);
            }
            currOffset++;
            countReturned++;
            recordArray.add(record);
          }
        }
      }
      retObj.put(ComObject.Tag.currOffset, currOffset);
      retObj.put(ComObject.Tag.countReturned, countReturned);

      return retObj;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  @Nullable
  private ComObject processServerSelectResults(ComObject cobj, int count, SelectStatementImpl select, Offset offset, Limit limit, ExpressionImpl.CachedRecord[][] results) {
    int currCount = 0;
    for (ExpressionImpl.CachedRecord[] records : results) {
      for (ExpressionImpl.CachedRecord record : records) {
        currCount++;
      }
    }
    if (currCount < count) {
      // exhausted results

      ComObject retObj = new ComObject();
      select.setIsOnServer(false);
      retObj.put(ComObject.Tag.legacySelectStatement, select.serialize());

      long currOffset = 0;
      if (cobj.getLong(ComObject.Tag.currOffset) != null) {
        currOffset = cobj.getLong(ComObject.Tag.currOffset);
      }
      long countReturned = 0;
      if (cobj.getLong(ComObject.Tag.countReturned) != null) {
        countReturned = cobj.getLong(ComObject.Tag.countReturned);
      }
      ComArray tableArray = retObj.putArray(ComObject.Tag.tableRecords, ComObject.Type.arrayType);
      outer:
      for (ExpressionImpl.CachedRecord[] tableRecords : results) {
        ComArray recordArray = null;

        for (ExpressionImpl.CachedRecord record : tableRecords) {
          if (offset != null && currOffset < offset.getOffset()) {
            currOffset++;
            continue;
          }
          if (limit != null && countReturned >= limit.getRowCount()) {
            break outer;
          }
          if (recordArray == null) {
            recordArray = tableArray.addArray(ComObject.Tag.records, ComObject.Type.byteArrayType);
          }
          currOffset++;
          countReturned++;
          byte[] bytes = record.getSerializedRecord();
          if (bytes == null) {
            bytes = record.getRecord().serialize(server.getCommon(), DatabaseClient.SERIALIZATION_VERSION);
          }
          recordArray.add(bytes);
        }
      }
      retObj.put(ComObject.Tag.currOffset, currOffset);
      retObj.put(ComObject.Tag.countReturned, countReturned);
      return retObj;
    }
    return null;
  }

  @SchemaReadLock
  public ComObject serverSetSelect(ComObject cobj, boolean replayedCommand) {
    return serverSetSelect(cobj, false, null);
  }

  public ComObject serverSetSelect(ComObject cobj, final boolean restrictToThisServer, final StoredProcedureContextImpl procedureContext) {
    try {
      final int schemaRetryCount = 0;
      if (server.getBatchRepartCount().get() != 0 && lookupCount.incrementAndGet() % 1000 == 0) {
        try {
          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          throw new DatabaseException(e);
        }
      }

      final String dbName = cobj.getString(ComObject.Tag.dbName);
      ComArray array = cobj.getArray(ComObject.Tag.selectStatements);
      final SelectStatementImpl[] selectStatements = new SelectStatementImpl[array.getArray().size()];
      for (int i = 0; i < array.getArray().size(); i++) {
        SelectStatementImpl stmt = new SelectStatementImpl(server.getClient());
        stmt.deserialize((byte[])array.getArray().get(i), dbName);
        selectStatements[i] = stmt;
      }
      ComArray tablesArray = cobj.getArray(ComObject.Tag.tables);
      String[] tableNames = new String[tablesArray.getArray().size()];
      TableSchema[] tableSchemas = new TableSchema[tableNames.length];
      for (int i = 0; i < array.getArray().size(); i++) {
        tableNames[i] = (String)tablesArray.getArray().get(i);
        tableSchemas[i] = server.getCommon().getTableSchema(dbName, tableNames[i], server.getDataDir());
      }

      List<OrderByExpressionImpl> orderByExpressions = new ArrayList<>();
      ComArray orderByArray = cobj.getArray(ComObject.Tag.orderByExpressions);
      if (orderByArray != null) {
        for (int i = 0; i < orderByArray.getArray().size(); i++) {
          OrderByExpressionImpl orderBy = new OrderByExpressionImpl();
          orderBy.deserialize((byte[]) orderByArray.getArray().get(i));
          orderByExpressions.add(orderBy);
        }
      }

      boolean notAll = false;
      ComArray operationsArray = cobj.getArray(ComObject.Tag.operations);
      String[] operations = new String[operationsArray.getArray().size()];
      for (int i = 0; i < operations.length; i++) {
        operations[i] = (String) operationsArray.getArray().get(i);
        if (!operations[i].toUpperCase().endsWith("ALL")) {
          notAll = true;
        }
      }

      long serverSelectPageNumber = cobj.getLong(ComObject.Tag.serverSelectPageNumber);
      long resultSetId = cobj.getLong(ComObject.Tag.resultSetId);

      int count = cobj.getInt(ComObject.Tag.count);

      DiskBasedResultSet diskResults = null;
      if (serverSelectPageNumber == 0) {
        diskResults = doInitialServerSetSelect(cobj, restrictToThisServer, procedureContext, schemaRetryCount, dbName, selectStatements, tableNames, orderByExpressions, notAll, operations, count);
      }
      else {
        diskResults = new DiskBasedResultSet(server, null, tableNames, resultSetId, restrictToThisServer, procedureContext);
      }
      byte[][][] records = diskResults.nextPage((int)serverSelectPageNumber);

      return processServerSetSelectResults(serverSelectPageNumber, diskResults, records);
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  @NotNull
  private ComObject processServerSetSelectResults(long serverSelectPageNumber, DiskBasedResultSet diskResults, byte[][][] records) {
    ComObject retObj = new ComObject();

    retObj.put(ComObject.Tag.resultSetId, diskResults.getResultSetId());
    retObj.put(ComObject.Tag.serverSelectPageNumber, serverSelectPageNumber + 1);
    retObj.put(ComObject.Tag.shard, server.getShard());
    retObj.put(ComObject.Tag.replica, server.getReplica());

    if (records != null) {
      ComArray tableArray = retObj.putArray(ComObject.Tag.tableRecords, ComObject.Type.arrayType);
      for (byte[][] tableRecords : records) {
        ComArray recordArray = tableArray.addArray(ComObject.Tag.records, ComObject.Type.byteArrayType);

        for (int i = 0; i < tableRecords.length; i++) {
          byte[] record = tableRecords[i];
          if (record == null) {
            recordArray.add(new byte[]{});
          }
          else {
            recordArray.add(record);
          }
        }
      }
    }
    return retObj;
  }

  private DiskBasedResultSet doInitialServerSetSelect(ComObject cobj, final boolean restrictToThisServer, final StoredProcedureContextImpl procedureContext, final int schemaRetryCount, final String dbName, final SelectStatementImpl[] selectStatements, String[] tableNames, List<OrderByExpressionImpl> orderByExpressions, boolean notAll, String[] operations, int count) throws InterruptedException, java.util.concurrent.ExecutionException {
    DiskBasedResultSet diskResults;ThreadPoolExecutor executor = ThreadUtil.createExecutor(selectStatements.length, "SonicBase ReadManager serverSetSelect Thread");
    final ResultSetImpl[] resultSets = new ResultSetImpl[selectStatements.length];
    try {
      List<Future> futures = new ArrayList<>();
      for (int i = 0; i < selectStatements.length; i++) {
        final int offset = i;
        futures.add(executor.submit(new Callable() {
          @Override
          public Object call() throws Exception {
            SelectStatementImpl stmt = selectStatements[offset];
            stmt.setPageSize(1000);
            resultSets[offset] = (ResultSetImpl) stmt.execute(dbName, null, null, null, null,
                null, restrictToThisServer, procedureContext, schemaRetryCount);
            return null;
          }
        }));
      }
      for (Future future : futures) {
        future.get();
      }
    }
    finally {
      executor.shutdownNow();
    }

    if (notAll) {
      diskResults = buildUniqueResultSet(cobj.getShort(ComObject.Tag.serializationVersion),
          dbName, selectStatements, tableNames, resultSets, orderByExpressions, count, operations);
    }
    else {
      int[] offsets = new int[tableNames.length];
      for (int i = 0; i < offsets.length; i++) {
        offsets[i] = i;
      }
      diskResults = new DiskBasedResultSet(cobj.getShort(ComObject.Tag.serializationVersion), dbName, server, null, null,
          tableNames, offsets, resultSets, orderByExpressions, count, null, true);
    }
    return diskResults;
  }

  private DiskBasedResultSet buildUniqueResultSet(final Short serializationVersion, final String dbName,
                                                  final SelectStatementImpl[] selectStatements, String[] tableNames,
                                                  final ResultSetImpl[] resultSets,
                                                  List<OrderByExpressionImpl> orderByExpressions, final int count, String[] operations) {
    final List<OrderByExpressionImpl> orderByUnique = new ArrayList<>();
    for (ColumnImpl column : selectStatements[0].getSelectColumns()) {
      String columnName = column.getColumnName();
      OrderByExpressionImpl orderBy = new OrderByExpressionImpl();
      orderBy.setAscending(true);
      orderBy.setColumnName(columnName);
      orderByUnique.add(orderBy);
    }
    boolean inMemory = true;
    for (int i = 0; i < resultSets.length; i++) {
      ExpressionImpl.CachedRecord[][] records = resultSets[0].getReadRecordsAndSerializedRecords();
      if (records != null && records.length >= 1000) {
        inMemory = false;
        break;
      }
    }
    Object[] diskResultSets = new Object[resultSets.length];
    if (inMemory) {
      for (int i = 0; i < resultSets.length; i++) {
        diskResultSets[i] = resultSets[i];

        ResultSetImpl.sortResults(dbName, server.getCommon(), resultSets[i].getReadRecordsAndSerializedRecords(),
            resultSets[i].getTableNames(), orderByUnique);
      }
    }
    else {
      ThreadPoolExecutor executor = ThreadUtil.createExecutor(resultSets.length, "SonicBase ReadManager buildUniqueResultSet Thread");
      List<Future> futures = new ArrayList<>();
      try {
        for (int i = 0; i < resultSets.length; i++) {
          final int offset = i;
          futures.add(executor.submit(new Callable() {
            @Override
            public Object call() throws Exception {
              return new DiskBasedResultSet(serializationVersion, dbName, server, null, null,
                  new String[]{selectStatements[offset].getFromTable()},
                  new int[]{offset}, new ResultSetImpl[]{resultSets[offset]}, orderByUnique, 30_000, null, true);
            }
          }));
        }
        for (int i = 0; i < futures.size(); i++) {
          diskResultSets[i] = (DiskBasedResultSet) futures.get(i).get();
        }
      }
      catch (Exception e) {
        throw new DatabaseException(e);
      }
      finally {
        executor.shutdownNow();
      }
    }

    Object ret = diskResultSets[0];
    for (int i = 1; i < resultSets.length; i++) {
      boolean unique = !operations[i-1].toUpperCase().endsWith("ALL");
      boolean intersect = operations[i-1].toUpperCase().startsWith("INTERSECT");
      boolean except = operations[i-1].toUpperCase().startsWith("EXCEPT");
      List<String> tables = new ArrayList<>();
      String[] localTableNames = ret instanceof ResultSetImpl ?
          ((ResultSetImpl)ret).getTableNames() : ((DiskBasedResultSet)ret).getTableNames();
      for (String tableName : localTableNames) {
        tables.add(tableName);
      }
      localTableNames = diskResultSets[i] instanceof ResultSetImpl ?
          ((ResultSetImpl)diskResultSets[i]).getTableNames() : ((DiskBasedResultSet)diskResultSets[i]).getTableNames();
      for (String tableName : localTableNames) {
        tables.add(tableName);
      }
      ret = new DiskBasedResultSet(serializationVersion, dbName, server,
          tables.toArray(new String[tables.size()]), new Object[]{ret, diskResultSets[i]},
          orderByExpressions, count, unique, intersect, except, selectStatements[0].getSelectColumns());
    }

    return (DiskBasedResultSet) ret;
  }

  @SchemaReadLock
  public ComObject indexLookupExpression(ComObject cobj, boolean replayedCommand) {
    return indexLookupExpression(cobj, null);
  }

  public ComObject indexLookupExpression(ComObject cobj, StoredProcedureContextImpl procedureContext) {
    try {
      if (server.getBatchRepartCount().get() != 0 && lookupCount.incrementAndGet() % 1000 == 0) {
        try {
          Thread.sleep(10);
        }
        catch (InterruptedException e) {
          throw new DatabaseException(e);
        }
      }

      IndexLookupWithExpression indexLookup = new IndexLookupWithExpression(server);
      String dbName = cobj.getString(ComObject.Tag.dbName);
      indexLookup.setDbName(dbName);
      Integer schemaVersion = cobj.getInt(ComObject.Tag.schemaVersion);
      if (schemaVersion != null && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException("currVer:" + server.getCommon().getSchemaVersion() + ":");
      }
      int count = cobj.getInt(ComObject.Tag.count);
      indexLookup.setCount(count);
      AtomicLong currOffset = new AtomicLong(cobj.getLong(ComObject.Tag.currOffset));
      Long limit = cobj.getLong(ComObject.Tag.limitLong);
      Long offset = cobj.getLong(ComObject.Tag.offsetLong);
      indexLookup.setCurrOffset(currOffset);
      indexLookup.setLimit(limit);
      indexLookup.setOffset(offset);

      int tableId = cobj.getInt(ComObject.Tag.tableId);
      byte[] parmBytes = cobj.getByteArray(ComObject.Tag.parms);
      ParameterHandler parms = null;
      if (parmBytes != null) {
        parms = new ParameterHandler();
        parms.deserialize(parmBytes);
      }
      indexLookup.setParms(parms);
      byte[] expressionBytes = cobj.getByteArray(ComObject.Tag.legacyExpression);
      Expression expression = null;
      if (expressionBytes != null) {
        expression = ExpressionImpl.deserializeExpression(expressionBytes);
      }
      indexLookup.setExpression(expression);
      String tableName = null;
      String indexName = null;
      try {
        TableSchema tableSchema = server.getCommon().getTablesById(dbName).get(tableId);
        tableName = tableSchema.getName();
        for (Map.Entry<String, IndexSchema> entry : tableSchema.getIndices().entrySet()) {
          if (entry.getValue().isPrimaryKey()) {
            indexName = entry.getKey();
          }
        }
      }
      catch (Exception e) {
        logger.info("indexLookup: tableName=" + tableName + ", tableid=" + tableId + ", tableByNameCount=" + server.getCommon().getTables(dbName).size() + ", tableCount=" + server.getCommon().getTablesById(dbName).size() +
            ", tableNull=" + (server.getCommon().getTablesById(dbName).get(tableId) == null) + ", indexName=" + indexName + ", indexName=" + indexName +
            ", indexNull=" /*+ (common.getTablesById().get(tableId).getIndexesById().get(indexId) == null) */);
        throw e;
      }
      indexLookup.setTableName(tableName);
      indexLookup.setIndexName(indexName);
      ComArray orderByArray = cobj.getArray(ComObject.Tag.orderByExpressions);
      List<OrderByExpressionImpl> orderByExpressions = new ArrayList<>();
      if (orderByArray != null) {
        for (int i = 0; i < orderByArray.getArray().size(); i++) {
          OrderByExpressionImpl orderByExpression = new OrderByExpressionImpl();
          orderByExpression.deserialize((byte[])orderByArray.getArray().get(i));
          orderByExpressions.add(orderByExpression);
        }
      }
      indexLookup.setOrderByExpressions(orderByExpressions);

      long viewVersion = cobj.getLong(ComObject.Tag.viewVersion);
      indexLookup.setViewVersion(viewVersion);
      TableSchema tableSchema = server.getCommon().getTableSchema(dbName, tableName, server.getDataDir());
      IndexSchema indexSchema = server.getIndexSchema(dbName, tableSchema.getName(), indexName);
      indexLookup.setTableSchema(tableSchema);
      indexLookup.setIndexSchema(indexSchema);
      byte[] leftKeyBytes = cobj.getByteArray(ComObject.Tag.leftKey);
      Object[] leftKey = null;
      if (leftKeyBytes != null) {
        leftKey = DatabaseCommon.deserializeTypedKey(leftKeyBytes);
        indexLookup.setLeftKey(leftKey);
      }
      byte[] rightKeyBytes = cobj.getByteArray(ComObject.Tag.rightKey);
      Object[] rightKey = null;
      if (rightKeyBytes != null) {
        rightKey = DatabaseCommon.deserializeTypedKey(rightKeyBytes);
        indexLookup.setRightKey(rightKey);
      }

      Integer rightOpValue = cobj.getInt(ComObject.Tag.rightOperator);
      BinaryExpression.Operator rightOperator = null;
      if (rightOpValue != null) {
        rightOperator = BinaryExpression.Operator.getOperator(rightOpValue);
        indexLookup.setRightOperator(rightOperator);
      }

      ComArray cOffsets = cobj.getArray(ComObject.Tag.columnOffsets);
      Set<Integer> columnOffsets = new HashSet<>();
      for (Object obj : cOffsets.getArray()) {
        columnOffsets.add((Integer)obj);
      }
      indexLookup.setColumnOffsets(columnOffsets);

      ComArray countersArray = cobj.getArray(ComObject.Tag.counters);
      Counter[] counters = null;
      if (countersArray != null) {
        counters = new Counter[countersArray.getArray().size()];
        for (int i = 0; i < counters.length; i++) {
          counters[i] = new Counter();
          counters[i].deserialize((byte[])countersArray.getArray().get(i));
        }
      }
      indexLookup.setCounters(counters);

      byte[] groupBytes = cobj.getByteArray(ComObject.Tag.legacyGroupContext);
      GroupByContext groupByContext = null;
      if (groupBytes != null) {
        groupByContext = new GroupByContext();
        groupByContext.deserialize(groupBytes, server.getCommon(), dbName);
      }
      indexLookup.setGroupContext(groupByContext);

      Boolean isProbe = cobj.getBoolean(ComObject.Tag.isProbe);
      if (isProbe == null) {
        isProbe = false;
      }
      indexLookup.setIsProbe(isProbe);
      Index index = server.getIndex(dbName, tableSchema.getName(), indexName);
      Map.Entry<Object[], Object> entry = null;
      indexLookup.setIndex(index);
      Boolean ascending = null;
      if (orderByExpressions.size() != 0) {
        OrderByExpressionImpl orderByExpression = orderByExpressions.get(0);
        String columnName = orderByExpression.getColumnName();
        boolean isAscending = orderByExpression.isAscending();
        if (columnName.equals(indexSchema.getFields()[0])) {
          ascending = isAscending;
        }
      }
      indexLookup.setAscending(ascending);
      List<byte[]> retRecords = new ArrayList<>();
      indexLookup.setRetRecords(retRecords);

      entry = indexLookup.lookup();

      ComObject retObj = new ComObject();
      if (entry != null) {
        retObj.put(ComObject.Tag.keyBytes, DatabaseCommon.serializeKey(tableSchema, indexName, entry.getKey()));
      }

      ComArray records = retObj.putArray(ComObject.Tag.records, ComObject.Type.byteArrayType);
      for (byte[] record : retRecords) {
        records.add(record);
      }

      if (counters != null) {
        countersArray = retObj.putArray(ComObject.Tag.counters, ComObject.Type.byteArrayType);
        for (int i = 0; i < counters.length; i++) {
          countersArray.add(counters[i].serialize());
        }
      }

      if (groupByContext != null) {
        retObj.put(ComObject.Tag.legacyGroupContext, groupByContext.serialize(server.getCommon()));
      }

      retObj.put(ComObject.Tag.currOffset, currOffset.get());

      return retObj;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }



  @SchemaReadLock
  public ComObject evaluateCounterGetKeys(ComObject cobj, boolean replayedCommand) {

    String dbName = cobj.getString(ComObject.Tag.dbName);

    Counter counter = new Counter();
    try {
      byte[] counterBytes = cobj.getByteArray(ComObject.Tag.legacyCounter);
      counter.deserialize(counterBytes);

      boolean isPrimaryKey = false;
      String tableName = counter.getTableName();
      String columnName = counter.getColumnName();
      String indexName = null;
      TableSchema tableSchema = server.getCommon().getTableSchema(dbName, tableName, server.getDataDir());
      for (IndexSchema indexSchema : tableSchema.getIndexes().values()) {
        if (indexSchema.getFields()[0].equals(columnName)) {
          isPrimaryKey = indexSchema.isPrimaryKey();
          indexName = indexSchema.getName();
          //break;
        }
      }
      byte[] maxKey = null;
      byte[] minKey = null;
      Index index = server.getIndex(dbName, tableName, indexName);
      Map.Entry<Object[], Object> entry = index.lastEntry();
      if (entry != null) {
        byte[][] records = null;
        Object unsafeAddress = entry.getValue();
        if (unsafeAddress != null && !unsafeAddress.equals(0L)) {
          records = server.getAddressMap().fromUnsafeToRecords(unsafeAddress);
        }
        if (records != null) {
          if (isPrimaryKey) {
            maxKey = DatabaseCommon.serializeKey(tableSchema, indexName, entry.getKey());
          }
          else {
            KeyRecord keyRecord = new KeyRecord(records[0]);
            maxKey = keyRecord.getPrimaryKey();
          }
        }
      }
      entry = index.firstEntry();
      if (entry != null) {
        byte[][] records = null;
        Object unsafeAddress = entry.getValue();
        if (unsafeAddress != null && !unsafeAddress.equals(0L)) {
          records = server.getAddressMap().fromUnsafeToRecords(unsafeAddress);
        }
        if (records != null) {
          if (isPrimaryKey) {
            minKey = DatabaseCommon.serializeKey(tableSchema, indexName, entry.getKey());
          }
          else {
            KeyRecord keyRecord = new KeyRecord(records[0]);
            minKey = keyRecord.getPrimaryKey();
          }
        }
      }
      if (minKey == null || maxKey == null) {
        logger.error("minkey==null || maxkey==null");
      }
      ComObject retObj = new ComObject();
      if (minKey != null) {
        retObj.put(ComObject.Tag.minKey, minKey);
      }
      if (maxKey != null) {
        retObj.put(ComObject.Tag.maxKey, maxKey);
      }
      retObj.put(ComObject.Tag.legacyCounter, counter.serialize());
      return retObj;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  @SchemaReadLock
  public ComObject evaluateCounterWithRecord(ComObject cobj, boolean replayedCommand) {

    String dbName = cobj.getString(ComObject.Tag.dbName);

    Counter counter = new Counter();
    try {
      byte[] minKeyBytes = cobj.getByteArray(ComObject.Tag.minKey);
      byte[] maxKeyBytes = cobj.getByteArray(ComObject.Tag.maxKey);
      byte[] counterBytes = cobj.getByteArray(ComObject.Tag.legacyCounter);
      counter.deserialize(counterBytes);

      String tableName = counter.getTableName();
      String columnName = counter.getColumnName();
      String indexName = null;
      TableSchema tableSchema = server.getCommon().getTableSchema(dbName, tableName, server.getDataDir());
      for (IndexSchema indexSchema : tableSchema.getIndexes().values()) {
        if (indexSchema.isPrimaryKey()) {
          indexName = indexSchema.getName();
        }
      }
      byte[] keyBytes = minKeyBytes;
      if (minKeyBytes == null) {
        keyBytes = maxKeyBytes;
      }
      Object[] key = DatabaseCommon.deserializeKey(tableSchema, keyBytes);

      Index index = server.getIndex(dbName, tableName, indexName);
      Object unsafeAddress = index.get(key);
      if (unsafeAddress != null) {
        byte[][] records = null;
        if (unsafeAddress != null && !unsafeAddress.equals(0L)) {
          records = server.getAddressMap().fromUnsafeToRecords(unsafeAddress);
        }
        if (records != null) {
          Record record = new Record(dbName, server.getCommon(), records[0]);
          Object value = record.getFields()[tableSchema.getFieldOffset(columnName)];
          if (minKeyBytes != null) {
            if (counter.isDestTypeLong()) {
              counter.setMinLong((Long) DataType.getLongConverter().convert(value));
            }
            else {
              counter.setMinDouble((Double) DataType.getDoubleConverter().convert(value));
            }
          }
          else {
            if (counter.isDestTypeLong()) {
              counter.setMaxLong((Long) DataType.getLongConverter().convert(value));
            }
            else {
              counter.setMaxDouble((Double) DataType.getDoubleConverter().convert(value));
            }
          }
        }
      }
      ComObject retObj = new ComObject();
      retObj.put(ComObject.Tag.legacyCounter, counter.serialize());
      return retObj;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }


}
