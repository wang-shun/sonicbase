package com.lowryengineering.database.query.impl;

import com.lowryengineering.database.client.DatabaseClient;
import com.lowryengineering.database.common.Record;
import com.lowryengineering.database.jdbcdriver.ParameterHandler;
import com.lowryengineering.database.query.BinaryExpression;
import com.lowryengineering.database.query.DatabaseException;
import com.lowryengineering.database.query.Expression;
import com.lowryengineering.database.query.InExpression;
import com.lowryengineering.database.schema.DataType;
import com.lowryengineering.database.schema.IndexSchema;
import com.lowryengineering.database.schema.TableSchema;
import com.lowryengineering.database.server.ReadManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class InExpressionImpl extends ExpressionImpl implements InExpression {
  private DatabaseClient client;
  private ParameterHandler parms;
  private String tableName;
  private List<ExpressionImpl> expressionList = new ArrayList<ExpressionImpl>();
  private ExpressionImpl leftExpression;
  private boolean isNot;

  public InExpressionImpl(DatabaseClient client, ParameterHandler parms, String tableName) {
    this.client = client;
    this.parms = parms;
    this.tableName = tableName;
  }

  public InExpressionImpl() {
  }

  public List<ExpressionImpl> getExpressionList() {
    return expressionList;
  }

  public void setTableName(String tableName) {
    super.setTableName(tableName);
    leftExpression.setTableName(tableName);
    if (this.expressionList != null) {
      for (ExpressionImpl expression : expressionList) {
        expression.setTableName(tableName);
      }
    }
  }

  public void setExpressionList(List<Expression> expressionList) {
    this.expressionList = new ArrayList<>();
    for (Expression expression : expressionList) {
      this.expressionList.add((ExpressionImpl)expression);
    }
  }

  public ExpressionImpl getLeftExpression() {
    return leftExpression;
  }


  public void setColumn(String tableName, String columnName, String alias) {
    this.leftExpression = new ColumnImpl(null, null, tableName.toLowerCase(), columnName.toLowerCase(), alias.toLowerCase());
  }

  public void addValue(String value) throws UnsupportedEncodingException {
    expressionList.add(new ConstantImpl(value.getBytes("utf-8"), DataType.Type.VARCHAR.getValue()));
  }

  public void addValue(long value) {
    expressionList.add(new ConstantImpl(value, DataType.Type.BIGINT.getValue()));
  }

  public void setLeftExpression(Expression leftExpression) {
    this.leftExpression = (ExpressionImpl)leftExpression;
  }

  @Override
  public void getColumns(Set<ColumnImpl> columns) {
    leftExpression.getColumns(columns);
  }

  public void setColumns(List<ColumnImpl> columns) {
    super.setColumns(columns);
    leftExpression.setColumns(columns);
  }

  @Override
  public void serialize(DataOutputStream out) {
    try {
      super.serialize(out);
      out.writeInt(expressionList.size());
      for (ExpressionImpl expression : expressionList) {
        ExpressionImpl.serializeExpression(expression, out);
      }
      out.writeInt(leftExpression.getType().getId());
      leftExpression.serialize(out);
      out.writeBoolean(isNot);
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  @Override
  public ExpressionImpl.Type getType() {
    return ExpressionImpl.Type.inExpression;
  }

  @Override
  public void deserialize(DataInputStream in) {
    try {
      super.deserialize(in);
      int count = in.readInt();
      for (int i = 0; i < count; i++) {
        ExpressionImpl expression = ExpressionImpl.deserializeExpression(in);
        expressionList.add(expression);
      }
      leftExpression = ExpressionImpl.deserializeExpression(in);
      isNot = in.readBoolean();
    }
    catch (IOException e) {
      throw new DatabaseException(e);
    }
  }

  @Override
  public Object evaluateSingleRecord(TableSchema[] tableSchemas, Record[] records, ParameterHandler parms) {
    Object lhsValue = leftExpression.evaluateSingleRecord(tableSchemas, records, parms);
    if (lhsValue == null) {
      return false;
    }
    Comparator comparator = DataType.Type.getComparatorForValue(lhsValue);
    for (ExpressionImpl expression : expressionList) {
      if (comparator.compare(lhsValue, expression.evaluateSingleRecord(tableSchemas, records, parms)) == 0) {
        if (isNot) {
          return false;
        }
        return true;
      }
    }
    if (isNot) {
      return true;
    }
    return false;
  }

  @Override
  public NextReturn next(int count) {
    if (getNextShard() == -2) {
      return new NextReturn(new String[]{getTableName()}, null);
    }
    if (isNot()) {
      SelectContextImpl context = ExpressionImpl.tableScan(dbName, getClient(), count, getClient().getCommon().getTables(dbName).get(getTableName()),
           getOrderByExpressions(), this, getParms(), getColumns(), getNextShard(), getNextKey(), getRecordCache(), getCounters(), getGroupByContext());
       if (context != null) {
         setNextShard(context.getNextShard());
         setNextKey(context.getNextKey());
         return new NextReturn(context.getTableNames(), context.getCurrKeys());
       }
      //return tableScan(count, getClient(), (ExpressionImpl) getTopLevelExpression(), getParms(), getTableName());
    }
    Object[][][] ret = null;
    IndexSchema indexSchema = null;
    List<ExpressionImpl> expressionList = getExpressionList();
    ColumnImpl cNode = (ColumnImpl) getLeftExpression();
    for (IndexSchema schema : client.getCommon().getSchema(dbName).getTables().get(tableName).getIndexes().values()) {
      if (schema.getFields()[0].equals(cNode.getColumnName())) {
        indexSchema = schema;
      }
    }
    if (indexSchema == null) {
      throw new DatabaseException("Not Supported");
    }

    for (ExpressionImpl inValue : expressionList) {

      Object value = ExpressionImpl.getValueFromExpression(parms, inValue);

      Object[] key = new Object[]{value};
      AtomicReference<String> usedIndex = new AtomicReference<>();
      SelectContextImpl currRet = ExpressionImpl.lookupIds(dbName, client.getCommon(), client, getReplica(), count,
          client.getCommon().getTables(dbName).get(tableName), indexSchema,
          BinaryExpression.Operator.equal, null,
          null, key, getParms(), this, null, key, null, getColumns(), cNode.getColumnName(), -1, getRecordCache(), usedIndex,
          false, getViewVersion(), getCounters(), getGroupByContext(), debug);
      ret = ExpressionImpl.aggregateResults(ret, currRet.getCurrKeys());
    }
    setNextShard(-2);
    return new NextReturn(new String[]{getTableName()}, ret);
  }


  public NextReturn next() {
    return next(ReadManager.SELECT_PAGE_SIZE);
  }

  @Override
  public boolean canUseIndex() {
    if (leftExpression instanceof ColumnImpl) {
      String columnName = ((ColumnImpl) leftExpression).getColumnName();

      for (Map.Entry<String, IndexSchema> entry : client.getCommon().getTables(dbName).get(tableName).getIndices().entrySet()) {
        if (entry.getValue().getFields()[0].equals(columnName)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public boolean canSortWithIndex() {
    return false;
  }

  @Override
  public void queryRewrite() {

  }

  @Override
  public ColumnImpl getPrimaryColumn() {
    return null;
  }

  public void addExpression(ExpressionImpl expression) {
    expressionList.add(expression);
  }

  public void setNot(boolean not) {
    this.isNot = not;
  }

  public boolean isNot() {
    return isNot;
  }
}
