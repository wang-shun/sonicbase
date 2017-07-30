package com.sonicbase.query;

import com.sonicbase.common.ExcludeRename;

@ExcludeRename
public class DatabaseException extends RuntimeException {
  public DatabaseException() {
  }
  public DatabaseException(String msg) {
    super(msg);
  }

  public DatabaseException(String msg, Throwable t) {
    super(msg, t);
  }

  public DatabaseException(Throwable e) {
    super(e);
  }
}
