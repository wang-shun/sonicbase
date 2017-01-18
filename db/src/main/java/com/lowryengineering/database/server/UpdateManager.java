package com.lowryengineering.database.server;

import com.lowryengineering.database.client.DatabaseClient;
import com.lowryengineering.database.common.DatabaseCommon;
import com.lowryengineering.database.common.Record;
import com.lowryengineering.database.common.SchemaOutOfSyncException;
import com.lowryengineering.database.index.Index;
import com.lowryengineering.database.index.Repartitioner;
import com.lowryengineering.database.query.BinaryExpression;
import com.lowryengineering.database.query.DatabaseException;
import com.lowryengineering.database.schema.FieldSchema;
import com.lowryengineering.database.schema.IndexSchema;
import com.lowryengineering.database.schema.TableSchema;
import com.lowryengineering.database.util.DataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Responsible for
 */
public class UpdateManager {

  private static Logger logger = LoggerFactory.getLogger(UpdateManager.class);


  private static final String CURR_VER_STR = "currVer:";
  private final DatabaseServer server;

  public UpdateManager(DatabaseServer databaseServer) {
    this.server = databaseServer;
  }

  public byte[] deleteIndexEntry(String command, byte[] body, boolean replayedCommand) {
    try {
      String[] parts = command.split(":");
      String dbName = parts[4];
      String tableName = parts[5];
      int schemaVersion = Integer.valueOf(parts[3]);
      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }

      TableSchema tableSchema = server.getCommon().getSchema(dbName).getTables().get(tableName);
      Record record = new Record(tableSchema);
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
      long serializationVersion = DataUtil.readVLong(in);
      int len = in.readInt();
      byte[] bytes = new byte[len];
      in.readFully(bytes);
      record.deserialize(dbName, server.getCommon(), bytes);
      List<FieldSchema> fieldSchemas = tableSchema.getFields();

      for (Map.Entry<String, IndexSchema> indexSchema : tableSchema.getIndices().entrySet()) {
        String[] fields = indexSchema.getValue().getFields();
        boolean shouldIndex = true;
        for (int i = 0; i < fields.length; i++) {
          boolean found = false;
          for (int j = 0; j < fieldSchemas.size(); j++) {
            if (fields[i].equals(fieldSchemas.get(j).getName())) {
              if (record.getFields()[j] != null) {
                found = true;
                break;
              }
            }
          }
          if (!found) {
            shouldIndex = false;
            break;
          }
        }
        if (shouldIndex) {
          String[] indexFields = indexSchema.getValue().getFields();
          Object[] key = new Object[indexFields.length];
          for (int i = 0; i < key.length; i++) {
            for (int j = 0; j < fieldSchemas.size(); j++) {
              if (fieldSchemas.get(j).getName().equals(indexFields[i])) {
                key[i] = record.getFields()[j];
              }
            }
          }
          Index index = server.getIndices(dbName).getIndices().get(tableSchema.getName()).get(indexSchema.getKey());
          synchronized (index) {
            Long obj = index.get(key);
            if (obj == null) {
              continue;
            }

            server.freeUnsafeIds(obj);
            index.remove(key);
          }
        }
      }

      return null;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  public byte[] populateIndex(String command, byte[] body) {
    command = command.replace(":populateIndex:", ":doPopulateIndex:");
    server.getLongRunningCommands().addCommand(server.getLongRunningCommands().createSingleCommand(command, body));
    return null;
  }

  public byte[] doPopulateIndex(final String command, byte[] body) {
    String[] parts = command.split(":");
    String dbName = parts[4];
    String tableName = parts[5];
    String indexName = parts[6];

    TableSchema tableSchema = server.getCommon().getTables(dbName).get(tableName);
    String primaryKeyIndexName = null;
    for (Map.Entry<String, IndexSchema> entry : tableSchema.getIndices().entrySet()) {
      if (entry.getValue().isPrimaryKey()) {
        primaryKeyIndexName = entry.getKey();
      }
    }

    Index primaryKeyIndex = server.getIndices().get(dbName).getIndices().get(tableName).get(primaryKeyIndexName);
    Map.Entry<Object[], Long> entry = primaryKeyIndex.firstEntry();
    while (entry != null) {
      byte[][] records = server.fromUnsafeToRecords(entry.getValue());
      for (int i = 0; i < records.length; i++) {
        Record record = new Record(dbName, server.getCommon(), records[i]);
        Object[] fields = record.getFields();
        List<String> columnNames = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int j = 0; j < fields.length; j++) {
          values.add(fields[j]);
          columnNames.add(tableSchema.getFields().get(j).getName());
        }

        DatabaseClient.KeyInfo primaryKey = new DatabaseClient.KeyInfo();
        tableSchema = server.getCommon().getTables(dbName).get(tableName);

        long id = 0;
        if (tableSchema.getFields().get(0).getName().equals("_id")) {
          id = (long) record.getFields()[0];
        }
        List<DatabaseClient.KeyInfo> keys = server.getDatabaseClient().getKeys(tableSchema, columnNames, values, id);

        for (final DatabaseClient.KeyInfo keyInfo : keys) {
          if (keyInfo.getIndexSchema().getValue().isPrimaryKey()) {
            primaryKey.setKey(keyInfo.getKey());
            primaryKey.setIndexSchema(keyInfo.getIndexSchema());
            break;
          }
        }
        for (final DatabaseClient.KeyInfo keyInfo : keys) {
          if (keyInfo.getIndexSchema().getKey().equals(indexName)) {
            server.getDatabaseClient().insertKey(dbName, tableName, keyInfo, primaryKeyIndexName, primaryKey.getKey());
          }
        }
      }
      entry = primaryKeyIndex.higherEntry(entry.getKey());
    }
    return null;
  }

  public byte[] deleteIndexEntryByKey(String command, byte[] body, boolean replayedCommand) {
    try {
      String[] parts = command.split(":");
      String dbName = parts[4];
      int schemaVersion = Integer.valueOf(parts[3]);
      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }
      String tableName = parts[5];
      String indexName = parts[6];
      String primaryKeyIndexName = parts[7];
      boolean isExplicitTrans = Boolean.valueOf(parts[8]);
      boolean isCommitting = Boolean.valueOf(parts[9]);
      long transactionId = Long.valueOf(parts[10]);

      TableSchema tableSchema = server.getCommon().getTables(dbName).get(tableName);
      ByteArrayInputStream bytesIn = new ByteArrayInputStream(body);
      DataInputStream in = new DataInputStream(bytesIn);
      long serializationVersion = DataUtil.readVLong(in);
      Object[] key = DatabaseCommon.deserializeKey(tableSchema, in);
      Object[] primaryKey = DatabaseCommon.deserializeKey(tableSchema, in);

      AtomicBoolean shouldExecute = new AtomicBoolean();
      AtomicBoolean shouldDeleteLock = new AtomicBoolean();

      server.getTransactionManager().preHandleTransaction(dbName, tableName, indexName, isExplicitTrans, isCommitting, transactionId, primaryKey, shouldExecute, shouldDeleteLock);

      if (shouldExecute.get()) {
        doRemoveIndexEntryByKey(dbName, tableSchema, primaryKeyIndexName, primaryKey, indexName, key);
      }

      if (shouldDeleteLock.get()) {
        server.getTransactionManager().deleteLock(dbName, tableName, indexName, transactionId, tableSchema, primaryKey);
      }
      return null;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public byte[] insertIndexEntryByKey(String command, byte[] body, boolean replayedCommand) {
    try {
      String[] parts = command.split(":");
      String dbName = parts[4];
      int schemaVersion = Integer.valueOf(parts[3]);
      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }
      String tableName = parts[5];
      String indexName = parts[6];
      boolean isExplicitTrans = Boolean.valueOf(parts[7]);
      boolean isCommitting = Boolean.valueOf(parts[8]);
      long transactionId = Long.valueOf(parts[9]);

      TableSchema tableSchema = server.getCommon().getTables(dbName).get(tableName);
      ByteArrayInputStream bytesIn = new ByteArrayInputStream(body);
      DataInputStream in = new DataInputStream(bytesIn);
      long serializationVersion = DataUtil.readVLong(in);
      DataUtil.ResultLength resultLength = new DataUtil.ResultLength();
      int len = (int) DataUtil.readVLong(in, resultLength);
      Object[] key = DatabaseCommon.deserializeKey(tableSchema, in);
      len = (int) DataUtil.readVLong(in, resultLength);
      byte[] primaryKeyBytes = new byte[len];
      in.readFully(primaryKeyBytes);


      Index index = server.getIndices(dbName).getIndices().get(tableSchema.getName()).get(indexName);

      Object[] primaryKey = DatabaseCommon.deserializeKey(tableSchema, new DataInputStream(new ByteArrayInputStream(primaryKeyBytes)));

      AtomicBoolean shouldExecute = new AtomicBoolean();
      AtomicBoolean shouldDeleteLock = new AtomicBoolean();

      server.getTransactionManager().preHandleTransaction(dbName, tableName, indexName, isExplicitTrans, isCommitting, transactionId, primaryKey, shouldExecute, shouldDeleteLock);

      if (shouldExecute.get()) {
        doInsertKey(key, primaryKeyBytes, index);
      }
      //    else {
      //      if (transactionId != 0) {
      //        Transaction trans = transactions.get(transactionId);
      //        Map<String, Index> indices = trans.indices.get(tableName);
      //        if (indices == null) {
      //          indices = new ConcurrentHashMap<>();
      //          trans.indices.put(tableName, indices);
      //        }
      //        index = indices.get(indexName);
      //        if (index == null) {
      //          Comparator[] comparators = tableSchema.getIndices().get(indexName).getComparators();
      //          index = new Index(tableSchema, indexName, comparators);
      //          indices.put(indexName, index);
      //        }
      //        long unsafe = toUnsafeFromKeys(new byte[][]{primaryKeyBytes});
      //        index.put(key, unsafe);
      //      }
      //    }


      if (shouldDeleteLock.get()) {
        server.getTransactionManager().deleteLock(dbName, tableName, indexName, transactionId, tableSchema, primaryKey);
      }

      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }

      return null;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  public byte[] insertIndexEntryByKeyWithRecord(String command, byte[] body, boolean replayedCommand) {
    try {
      String[] parts = command.split(":");
      String dbName = parts[4];
      int schemaVersion = Integer.valueOf(parts[3]);
      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }
      String tableName = parts[5];
      String indexName = parts[6];
      long id = Long.valueOf(parts[7]);
      boolean isExplicitTrans = Boolean.valueOf(parts[8]);
      boolean isCommitting = Boolean.valueOf(parts[9]);
      long transactionId = Long.valueOf(parts[10]);

      TableSchema tableSchema = server.getCommon().getTables(dbName).get(tableName);
      ByteArrayInputStream bytesIn = new ByteArrayInputStream(body);
      DataInputStream in = new DataInputStream(bytesIn);
      long serializationVersion = DataUtil.readVLong(in);
      int len = in.readInt();
      byte[] recordBytes = new byte[len];
      in.readFully(recordBytes);
      Object[] primaryKey = DatabaseCommon.deserializeKey(tableSchema, in);

      AtomicBoolean shouldExecute = new AtomicBoolean();
      AtomicBoolean shouldDeleteLock = new AtomicBoolean();
      server.getTransactionManager().preHandleTransaction(dbName, tableName, indexName, isExplicitTrans, isCommitting, transactionId, primaryKey, shouldExecute, shouldDeleteLock);

      List<Integer> selectedShards = null;
      IndexSchema indexSchema = server.getCommon().getTables(dbName).get(tableName).getIndexes().get(indexName);
      Index index = server.getIndices(dbName).getIndices().get(tableSchema.getName()).get(indexName);
      boolean alreadyExisted = false;
      if (shouldExecute.get()) {

        String[] indexFields = indexSchema.getFields();
        int[] fieldOffsets = new int[indexFields.length];
        for (int i = 0; i < indexFields.length; i++) {
          fieldOffsets[i] = tableSchema.getFieldOffset(indexFields[i]);
        }
        selectedShards = Repartitioner.findOrderedPartitionForRecord(true, false, fieldOffsets, server.getCommon(), tableSchema,
            indexName, null, BinaryExpression.Operator.equal, null, primaryKey, null);

//        if (null != index.get(primaryKey)) {
//          alreadyExisted = true;
//        }
        doInsertKey(id, recordBytes, primaryKey, index, tableSchema.getName(), indexName);

        if (indexSchema.getCurrPartitions()[selectedShards.get(0)].getShardOwning() != server.getShard()) {
          server.getRepartitioner().deleteIndexEntry(tableName, indexName, primaryKey);
        }
      }
      else {
        throw new DatabaseException("in trans");
//        if (transactionId != 0) {
//          TransactionManager.Transaction trans = server.getTransactionManager().getTransaction(transactionId);
//          List<Record> records = trans.getRecords().get(tableName);
//          if (records == null) {
//            records = new ArrayList<>();
//            trans.getRecords().put(tableName, records);
//          }
//          Record record = new Record(server.getCommon(), recordBytes);
//          records.add(record);
//        }
      }

      if (shouldDeleteLock.get()) {
        server.getTransactionManager().deleteLock(dbName, tableName, indexName, transactionId, tableSchema, primaryKey);
      }

      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {

        if (selectedShards != null) {
//          if (!alreadyExisted) {
//            synchronized (index) {
//              Long existingValue = index.remove(primaryKey);
//              if (existingValue != null) {
//                server.freeUnsafeIds(existingValue);
//              }
//            }
//          }

          if (indexSchema.getCurrPartitions()[selectedShards.get(0)].getShardOwning() != server.getShard()) {
            if (server.getRepartitioner().undeleteIndexEntry(dbName, tableName, indexName, primaryKey, recordBytes)) {
              doInsertKey(id, recordBytes, primaryKey, index, tableSchema.getName(), indexName);
            }
          }
        }
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }

      ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytesOut);
      DataUtil.writeVLong(out, SnapshotManager.SNAPSHOT_SERIALIZATION_VERSION);
      out.writeInt(1);
      out.close();
      return bytesOut.toByteArray();
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  public byte[] updateRecord(String command, byte[] body, boolean replayedCommand) {
    try {
      String[] parts = command.split(":");
      String dbName = parts[4];
      int schemaVersion = Integer.valueOf(parts[3]);
      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }
      String tableName = parts[5];
      String indexName = parts[6];
      boolean isExplicitTrans = Boolean.valueOf(parts[7]);
      boolean isCommitting = Boolean.valueOf(parts[8]);
      long transactionId = Long.valueOf(parts[9]);

      TableSchema tableSchema = server.getCommon().getTables(dbName).get(tableName);
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
      long serializationVersion = DataUtil.readVLong(in);
      Object[] primaryKey = DatabaseCommon.deserializeKey(tableSchema, in);
      int len = in.readInt();
      byte[] bytes = new byte[len];
      in.read(bytes);


      AtomicBoolean shouldExecute = new AtomicBoolean();
      AtomicBoolean shouldDeleteLock = new AtomicBoolean();

      server.getTransactionManager().preHandleTransaction(dbName, tableName, indexName, isExplicitTrans, isCommitting, transactionId, primaryKey, shouldExecute, shouldDeleteLock);

      if (shouldExecute.get()) {
        //because this is the primary key index we won't have more than one index entry for the key
        Index index = server.getIndices(dbName).getIndices().get(tableName).get(indexName);
        Long value = index.get(primaryKey);
        Long newValue = server.toUnsafeFromRecords(new byte[][]{bytes});
        index.put(primaryKey, newValue);
        if (value != null && value != 0) {
          server.freeUnsafeIds(value);
        }
      }
      else {
        if (transactionId != 0) {
          if (transactionId != 0) {
            TransactionManager.Transaction trans = server.getTransactionManager().getTransaction(transactionId);
            List<Record> records = trans.getRecords().get(tableName);
            if (records == null) {
              records = new ArrayList<>();
              trans.getRecords().put(tableName, records);
            }
            Record record = new Record(dbName, server.getCommon(), bytes);
            records.add(record);
          }
        }
      }

      if (shouldDeleteLock.get()) {
        server.getTransactionManager().deleteLock(dbName, tableName, indexName, transactionId, tableSchema, primaryKey);
      }
      return null;
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  private void doInsertKey(
      long id, byte[] recordBytes, Object[] key, Index index, String tableName, String indexName) throws IOException, DatabaseException {
    doActualInsertKeyWithRecord(recordBytes, key, index, tableName, indexName, false);
  }

  private void doInsertKey(Object[] key, byte[] primaryKeyBytes, Index index) {
    //    ArrayBlockingQueue<Entry> existing = insertQueue.computeIfAbsent(index, k -> new ArrayBlockingQueue<>(1000));
    //    insertThreads.computeIfAbsent(index, k -> createThread(index));

    doActualInsertKey(key, primaryKeyBytes, index);

    //    Entry currEntry = new Entry(id, key);
    //    existing.put(currEntry);
    //    currEntry.latch.await();
  }

  public void doInsertKeys(
      List<Repartitioner.MoveRequest> moveRequests, Index index, String tableName, IndexSchema indexSchema) {
    //    ArrayBlockingQueue<Entry> existing = insertQueue.computeIfAbsent(index, k -> new ArrayBlockingQueue<>(1000));
    //    insertThreads.computeIfAbsent(index, k -> createThread(index));

    if (indexSchema.isPrimaryKey()) {
      for (Repartitioner.MoveRequest moveRequest : moveRequests) {
        byte[][] content = moveRequest.getContent();
        for (int i = 0; i < content.length; i++) {
          doActualInsertKeyWithRecord(content[i], moveRequest.getKey(), index, tableName, indexSchema.getName(), true);
        }
      }
    }
    else {
      for (Repartitioner.MoveRequest moveRequest : moveRequests) {
        byte[][] content = moveRequest.getContent();
        for (int i = 0; i < content.length; i++) {
          doActualInsertKey(moveRequest.getKey(), content[i], index);
        }
      }
    }

    //    Entry currEntry = new Entry(id, key);
    //    existing.put(currEntry);
    //    currEntry.latch.await();
  }

  /**
   * Caller must synchronized index
   */
  private void doActualInsertKey(Object[] key, byte[] primaryKeyBytes, Index index) {
    int fieldCount = index.getComparators().length;
    if (fieldCount != key.length) {
      Object[] newKey = new Object[fieldCount];
      for (int i = 0; i < newKey.length; i++) {
        newKey[i] = key[i];
      }
      key = newKey;
    }
    synchronized (index) {
      Long existingValue = index.get(key);
      if (existingValue == null) {
        index.put(key, server.toUnsafeFromKeys(new byte[][]{primaryKeyBytes}));
      }
      else {
        byte[][] records = server.fromUnsafeToRecords(existingValue);
        boolean replaced = false;
        for (int i = 0; i < records.length; i++) {
          if (Arrays.equals(records[i], primaryKeyBytes)) {
            replaced = true;
            break;
          }
        }
        if (!replaced) {
          byte[][] newRecords = new byte[records.length + 1][];
          System.arraycopy(records, 0, newRecords, 0, records.length);
          newRecords[newRecords.length - 1] = primaryKeyBytes;
          long address = server.toUnsafeFromRecords(newRecords);
          server.freeUnsafeIds(existingValue);
          index.put(key, address);
        }
      }
    }
  }

  /**
   * Caller must synchronized index
   */
  private void doActualInsertKeyWithRecord(
      byte[] recordBytes, Object[] key, Index index, String tableName, String indexName, boolean ignoreDuplicates) {
//    int fieldCount = index.getComparators().length;
//    if (fieldCount != key.length) {
//      Object[] newKey = new Object[fieldCount];
//      for (int i = 0; i < newKey.length; i++) {
//        newKey[i] = key[i];
//      }
//      key = newKey;
//    }
    if (recordBytes == null) {
      throw new DatabaseException("Invalid record, null");
    }

    server.getRepartitioner().notifyAdded(key, tableName, indexName);

    long newUnsafeRecords = server.toUnsafeFromRecords(new byte[][]{recordBytes});

    if (true) {
      Long existingValue = index.unsafePutIfAbsent(key, newUnsafeRecords);
      if (existingValue != null) {
        //synchronized (index) {
        boolean sameTrans = false;
        byte[][] bytes = server.fromUnsafeToRecords(existingValue);
        long transId = Record.getTransId(recordBytes);
        for (byte[] innerBytes : bytes) {
          if (Record.getTransId(innerBytes) == transId) {
            sameTrans = true;
            break;
          }
        }
        if (!ignoreDuplicates && existingValue != null && !sameTrans) {
          throw new DatabaseException("Unique constraint violated");
        }

        index.put(key, newUnsafeRecords);

        server.freeUnsafeIds(existingValue);
      }
    }
    else {
      Long existingValue = index.get(key);
      boolean sameTrans = false;
      if (existingValue != null) {
        byte[][] bytes = server.fromUnsafeToRecords(existingValue);
        long transId = Record.getTransId(recordBytes);
        for (byte[] innerBytes : bytes) {
          if (Record.getTransId(innerBytes) == transId) {
            sameTrans = true;
            break;
          }
        }
      }
      if (!ignoreDuplicates && existingValue != null && !sameTrans) {
        throw new DatabaseException("Unique constraint violated");
      }
      //    if (existingValue == null) {
      index.put(key, server.toUnsafeFromRecords(new byte[][]{recordBytes}));
      if (existingValue != null) {
        server.freeUnsafeIds(existingValue);
      }
    }


    //    if (existingValue == null) {
    //}
    //    }
    //    else {
    //      byte[][] records = fromUnsafeToRecords(existingValue);
    //      boolean replaced = false;
    //      for (int i = 0; i < records.length; i++) {
    //        if (Arrays.equals(records[i], primaryKeyBytes)) {
    //          replaced = true;
    //          break;
    //        }
    //      }
    //      if (!replaced) {
    //        //logger.info("Replacing: table=" + tableName + ", index=" + indexName + ", key=" + key[0]);
    //        byte[][] newRecords = new byte[records.length + 1][];
    //        System.arraycopy(records, 0, newRecords, 0, records.length);
    //        newRecords[newRecords.length - 1] = recordBytes;
    //        long address = toUnsafeFromRecords(newRecords);
    //        freeUnsafeIds(existingValue);
    //        index.put(key, address);
    //      }
    //    }
  }

  //  public void indexKey(TableSchema schema, String indexName, Object[] key, long id) throws IOException {
  //
  //    //todo: add synchronization
  //    Index index = indexes.getIndices().get(schema.getName()).get(indexName);
  //    synchronized (index) {
  //      Object existingValue = index.put(key, id);
  //
  //      if (existingValue != null) {
  //        if (existingValue instanceof Long) {
  //          long[] records = new long[2];
  //          records[0] = (Long) existingValue;
  //          records[1] = id;
  //          if (records[0] != records[1]) {
  //            index.put(key, records);
  //          }
  //        }
  //        else {
  //          Long[] existingRecords = (Long[]) existingValue;
  //          boolean replaced = false;
  //          for (int i = 0; i < existingRecords.length; i++) {
  //            if (existingRecords[i] == id) {
  //              replaced = true;
  //              break;
  //            }
  //          }
  //          if (!replaced) {
  //            long[] records = new long[existingRecords.length + 1];
  //
  //            System.arraycopy(existingRecords, 0, records, 0, existingRecords.length);
  //            records[records.length - 1] = id;
  //            index.put(key, records);
  //          }
  //        }
  //      }
  //    }
  //  }

  public byte[] deleteRecord(String command, byte[] body, boolean replayedCommand) {
    try {
      String[] parts = command.split(":");
      String dbName = parts[4];
      String tableName = parts[5];
      String indexName = parts[6];
      int schemaVersion = Integer.valueOf(parts[3]);
      if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
        throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
      }

      DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
      long serializationVersion = DataUtil.readVLong(in);

      TableSchema tableSchema = server.getCommon().getTables(dbName).get(tableName);
      Object[] key = DatabaseCommon.deserializeKey(tableSchema, in);

      Index index = server.getIndices(dbName).getIndices().get(tableName).get(indexName);
      synchronized (index) {
        Long value = index.get(key);
        if (value != null) {
          server.freeUnsafeIds(value);
        }
        index.remove(key);
      }

      return null;
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }

  public byte[] truncateTable(String command, byte[] body, boolean replayedCommand) {
    String[] parts = command.split(":");
    String dbName = parts[4];
    int schemaVersion = Integer.valueOf(parts[3]);
    if (!replayedCommand && schemaVersion < server.getSchemaVersion()) {
      throw new SchemaOutOfSyncException(CURR_VER_STR + server.getCommon().getSchemaVersion() + ":");
    }
    String table = parts[5];
    String phase = parts[6];
    TableSchema tableSchema = server.getCommon().getTables(dbName).get(table);
    for (Map.Entry<String, IndexSchema> entry : tableSchema.getIndices().entrySet()) {
      Index index = server.getIndices(dbName).getIndices().get(table).get(entry.getKey());
      if (entry.getValue().isPrimaryKey()) {
        if (phase.equals("primary")) {
          Map.Entry<Object[], Long> indexEntry = index.firstEntry();
          do {
            if (indexEntry == null) {
              break;
            }
            server.freeUnsafeIds(indexEntry.getValue());
            index.remove(indexEntry.getKey());
            indexEntry = index.higherEntry(indexEntry.getKey());
          }
          while (true);
        }
      }
      else if (phase.equals("secondary")) {
        Map.Entry<Object[], Long> indexEntry = index.firstEntry();
        do {
          if (indexEntry == null) {
            break;
          }
          server.freeUnsafeIds(indexEntry.getValue());
          index.remove(indexEntry.getKey());
          indexEntry = index.higherEntry(indexEntry.getKey());
        }
        while (true);
      }
    }
    return null;
  }

//  public void removeRecordFromAllIndices(TableSchema schema, Record record) throws IOException {
//     Map<String, IndexSchema> tableIndexes = schema.getIndices();
//     for (Map.Entry<String, IndexSchema> entry : tableIndexes.entrySet()) {
//       String[] indexFields = entry.getValue().getFields();
//       Object[] indexEntries = new Object[indexFields.length];
//       boolean indexedAValue = false;
//       for (int i = 0; i < indexFields.length; i++) {
//         int offset = schema.getFieldOffset(indexFields[i]);
//         indexEntries[i] = record.getFields()[offset];
//         if (indexEntries[i] != null) {
//           indexedAValue = true;
//         }
//       }
// //      if (indexedAValue) {
// //
// //        doRemoveIndexEntryByKey(schema, record.getId(), indexName, indexEntries);
// //      }
//     }
//   }

  private void doRemoveIndexEntryByKey(
      String dbName, TableSchema schema, String primaryKeyIndexName, Object[] primaryKey, String indexName,
      Object[] key) {

    Comparator[] comparators = schema.getIndices().get(primaryKeyIndexName).getComparators();

    synchronized (server.getIndices(dbName).getIndices().get(schema.getName()).get(indexName)) {
      Long value = server.getIndices(dbName).getIndices().get(schema.getName()).get(indexName).get(key);
      if (value == null) {
        return;
      }
      else {
        byte[][] ids = server.fromUnsafeToKeys(value);
        if (ids.length == 1) {
          boolean mismatch = false;
          if (!indexName.equals(primaryKeyIndexName)) {
            Object[] lhsKey = DatabaseCommon.deserializeKey(schema, new DataInputStream(new ByteArrayInputStream(ids[0])));
            for (int i = 0; i < lhsKey.length; i++) {
              if (0 != comparators[i].compare(lhsKey[i], primaryKey[i])) {
                mismatch = true;
              }
            }
          }
          if (!mismatch) {
            server.freeUnsafeIds(value);
            server.getIndices(dbName).getIndices().get(schema.getName()).get(indexName).remove(key);
          }
        }
        else {
          byte[][] newValues = new byte[ids.length - 1][];
          int offset = 0;
          boolean found = false;
          for (byte[] currValue : ids) {
            boolean mismatch = false;
            Object[] lhsKey = DatabaseCommon.deserializeKey(schema, new DataInputStream(new ByteArrayInputStream(currValue)));
            for (int i = 0; i < lhsKey.length; i++) {
              if (0 != comparators[i].compare(lhsKey[i], primaryKey[i])) {
                mismatch = true;
              }
            }

            if (mismatch) {
              newValues[offset++] = currValue;
            }
            else {
              found = true;
            }
          }
          if (found) {
            server.freeUnsafeIds(value);
            value = server.toUnsafeFromKeys(newValues);
            server.getIndices(dbName).getIndices().get(schema.getName()).get(indexName).put(key, value);
          }
        }
      }
    }
  }
}
