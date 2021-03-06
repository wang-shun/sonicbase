package com.sonicbase.query.impl;


import java.io.*;

@SuppressWarnings({"squid:S1168", "squid:S00107"})
// I prefer to return null instead of an empty array
// I don't know a good way to reduce the parameter count
public class OrderByExpressionImpl {
  private String tableName;
  private String columnName;
  private boolean isAscending;

  public OrderByExpressionImpl() {
  }

  public OrderByExpressionImpl(String tableName, String columnName, boolean isAscending) {
    this.tableName = tableName;
    this.columnName = columnName;
    this.isAscending = isAscending;
  }

  public byte[] serialize() throws IOException {
    ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytesOut);
    serialize(out);
    out.close();
    return bytesOut.toByteArray();
  }

  public void serialize(DataOutputStream out) throws IOException {
    if (tableName == null) {
      out.writeInt(0);
    }
    else {
      out.writeInt(1);
      out.writeUTF(tableName);
    }
    out.writeUTF(columnName);
    out.writeBoolean(isAscending);
  }

  public void deserialize(byte[] bytes) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    deserialize(in);
  }

  public void deserialize(DataInputStream in) throws IOException {
    if (in.readInt() == 1) {
      tableName = in.readUTF();
    }
    columnName = in.readUTF();
    isAscending = in.readBoolean();
  }

  public String getTableName() {
    return tableName;
  }

  public void setTableName(String tableName) {
    this.tableName = tableName.toLowerCase();
  }

  public String getColumnName() {
    return columnName;
  }

  public void setColumnName(String columnName) {
    this.columnName = columnName.toLowerCase();
  }

  public boolean isAscending() {
    return isAscending;
  }

  public void setAscending(boolean ascending) {
    isAscending = ascending;
  }
}
