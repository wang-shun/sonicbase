/* © 2018 by Intellectual Reserve, Inc. All rights reserved. */
package com.sonicbase.website;

import java.sql.*;

public class Tutorial {
  public static void main(String[] args) throws ClassNotFoundException, SQLException {
    Class.forName("com.sonicbase.jdbcdriver.Driver");
    try (Connection conn = DriverManager.getConnection("jdbc:sonicbase:127.0.0.1:9010/db");
         PreparedStatement stmt = conn.prepareStatement("select * from persons");
         ResultSet rs = stmt.executeQuery()) {
      rs.next();
      System.out.println(rs.getString("name") + " " + rs.getInt("age") + " " + rs.getString("ssn"));
    }
  }
}
