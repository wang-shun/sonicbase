package com.sonicbase.jdbcdriver;

/**
 * User: lowryda
 * Date: 10/25/14
 * Time: 9:42 AM
 */

import com.sonicbase.client.DatabaseClient;
import com.sonicbase.common.Logger;
import com.sonicbase.query.DatabaseException;

import java.sql.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by IntelliJ IDEA.
 * User: lowryda
 * Date: Oct 7, 2011
 * Time: 3:09:46 PM
 */
public class ConnectionProxy implements Connection {

  private static final Object clientMutex = new Object();
  private static DatabaseClient databaseClient;
  private final String dbName;
  private boolean autoCommit;
  private java.util.Map<String, Class<?>> typemap;
  private int rsHoldability;
  private Properties _clientInfo;
  private Properties properties;
  private boolean closed = false;
  private int shard;


  public ConnectionProxy(String url, Properties properties) throws SQLException {

    this.properties = properties;
    initGlobalContext();
    try {
      String[] outerParts = url.split("/");
      String[] parts = outerParts[0].split(":");
      String host = parts[2];
      int port = Integer.valueOf(parts[3]);
      String db = null;
      if (outerParts.length > 1) {
        db = outerParts[1];
        db = db.toLowerCase();
      }
      synchronized (clientMutex) {
        if (databaseClient != null) {
          databaseClient.shutdown();
        }
        databaseClient = new DatabaseClient(host, port, -1, -1, true);
        Logger.setIsClient(true);
        Logger.setReady();
      }
      if (db != null) {
        databaseClient.initDb(db);
      }
      this.dbName = db;
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public String getDbName() {
    return dbName;
  }
  private static final AtomicInteger globalContextRefCount = new AtomicInteger();

  public DatabaseClient getDatabaseClient() {
    return databaseClient;
  }

  private void initGlobalContext() {
    globalContextRefCount.incrementAndGet();
  }

  protected void checkClosed() throws SQLException {
      if (isClosed()) {
        throw new SQLException("This connection has been closed.");
      }
  }

  public Statement createStatement() throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, null);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public void beginExplicitTransaction(String dbName) throws SQLException {
    try {
      databaseClient.beginExplicitTransaction(dbName);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public boolean getAutoCommit() throws SQLException {
    return autoCommit;
  }

  public void commit() throws SQLException {
    try {
      databaseClient.commit(dbName, null);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public void rollback() throws SQLException {
    try {
      databaseClient.rollback(dbName);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public String nativeSQL(String sql) throws SQLException {
    throw new NotImplementedException();
  }

  public void setAutoCommit(boolean autoCommit) throws SQLException {
    try {
      this.autoCommit = autoCommit;
      if (!autoCommit) {
        beginExplicitTransaction(dbName);
      }
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public void close() throws SQLException {
    try {
      if (closed) {
        throw new SQLException("Attempting to close a connection that is already closed");
      }
      closed = true;
      databaseClient.shutdown();
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public boolean isClosed() throws SQLException {
    return closed;
  }

  public DatabaseMetaData getMetaData() throws SQLException {
    throw new NotImplementedException();
  }

  public void setReadOnly(boolean readOnly) throws SQLException {
    throw new NotImplementedException();
  }

  public boolean isReadOnly() throws SQLException {
    return false;
  }

  public void setCatalog(String catalog) throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public String getCatalog() throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public void setTransactionIsolation(int level) throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public int getTransactionIsolation() throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public SQLWarning getWarnings() throws SQLException {
    try {
      checkClosed();
      StringBuilder ret = new StringBuilder();
      if (ret.length() == 0) {
        return null;
      }
      return new SQLWarning(ret.toString());
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public void clearWarnings() throws SQLException {
    try {
      checkClosed();
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public PreparedStatement prepareStatement(String sql) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, sql);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public CallableStatement prepareCall(String sql) throws SQLException {
    throw new SQLException("not supported");
  }

  public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, null);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, null);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, sql);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    throw new SQLException("not supported");
  }

  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, sql);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, sql);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, sql);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
    try {
      return new StatementProxy(this, databaseClient, sql);
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
    throw new SQLException("not supported");
  }

  public Map<String, Class<?>> getTypeMap() throws SQLException {
    try {
      checkClosed();
      return typemap;
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    typemap = map;
  }

  public void setHoldability(int holdability) throws SQLException
  {
    try {
      checkClosed();

      switch (holdability)
      {
      case ResultSet.CLOSE_CURSORS_AT_COMMIT:
          rsHoldability = holdability;
          break;
      case ResultSet.HOLD_CURSORS_OVER_COMMIT:
          rsHoldability = holdability;
          break;
      default:
          throw new SQLException("Unknown ResultSet holdability setting: " + holdability);
      }
    }
    catch (Exception e) {
      throw new SQLException(e);
    }

  }

  public int getHoldability() throws SQLException {
    return rsHoldability;
  }

  public Savepoint setSavepoint() throws SQLException {
    throw new NotImplementedException();
  }

  public Savepoint setSavepoint(String name) throws SQLException {
    throw new NotImplementedException();
  }

  public void rollback(Savepoint savepoint) throws SQLException {
    throw new NotImplementedException();
  }

  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    throw new NotImplementedException();
  }

  public Clob createClob() throws SQLException {
    return new com.sonicbase.query.impl.Clob();
  }

  public Blob createBlob() throws SQLException {
    return new com.sonicbase.query.impl.Blob();
  }

  public NClob createNClob() throws SQLException {
    return new com.sonicbase.query.impl.NClob();
  }

  public SQLXML createSQLXML() throws SQLException {
    throw new NotImplementedException();
  }

  public boolean isValid(int timeout) throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public void setClientInfo(String name, String value) throws SQLClientInfoException {
    Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
    failures.put(name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
    throw new SQLClientInfoException(failures);
  }

  public void setClientInfo(Properties properties) throws SQLClientInfoException {
    if (properties == null || properties.size() == 0)
        return;

    Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();

    Iterator<String> i = properties.stringPropertyNames().iterator();
    while (i.hasNext()) {
        failures.put(i.next(), ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
    }
    throw new SQLClientInfoException(failures);
  }

  public String getClientInfo(String name) throws SQLException {
    try {
      checkClosed();
      return null;
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public Properties getClientInfo() throws SQLException {
    try {
      checkClosed();
      if (_clientInfo == null) {
          _clientInfo = new Properties();
      }
      return _clientInfo;
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    //todo: implement
    throw new NotImplementedException();
  }

  public void setSchema(String schema) throws SQLException {
    // todo: implement for JDK 1.7
    throw new NotImplementedException();
  }

  public String getSchema() throws SQLException {
    // todo: implement for JDK 1.7
    throw new NotImplementedException();
  }

  public void abort(Executor executor) throws SQLException {
    // todo: implement for JDK 1.7
    throw new NotImplementedException();
  }

  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    // todo: implement for JDK 1.7
    throw new NotImplementedException();
  }

  public int getNetworkTimeout() throws SQLException {
    // todo: implement for JDK 1.7
    throw new NotImplementedException();
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    //todo: validate cast
    return (T) this;
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    //todo: validate iface
    throw new NotImplementedException();
  }

  public void createDatabase(String dbName) {
    try {
      databaseClient.createDatabase(dbName);
    }
    catch (Exception e) {
      throw new DatabaseException(e);
    }
  }
}
