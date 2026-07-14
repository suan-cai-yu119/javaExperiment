 package com.database.common;
 
 import java.io.Serial;
 import java.io.Serializable;
 
 /**
  * 请求对象 - 客户端发送给服务器的请求
  * 使用 Serializable 支持网络传输
  */
 public class Request implements Serializable {
     @Serial
     private static final long serialVersionUID = 1L;
     
     private CommandType command;
     private String[] args;
     private String databaseName;
     private String collectionName;
      private String key;
      private Object value;
      private String filterField;
      private Object filterValue;
      private long timestamp;
     
     public Request() {
         this.timestamp = System.currentTimeMillis();
     }
     
     public Request(CommandType command, String... args) {
         this();
         this.command = command;
         this.args = args;
     }
     
     // Getters and Setters
     public CommandType getCommand() { return command; }
     public void setCommand(CommandType command) { this.command = command; }
     public String[] getArgs() { return args; }
     public void setArgs(String[] args) { this.args = args; }
     public String getDatabaseName() { return databaseName; }
     public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
     public String getCollectionName() { return collectionName; }
     public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
     public String getKey() { return key; }
     public void setKey(String key) { this.key = key; }
      public Object getValue() { return value; }
      public void setValue(Object value) { this.value = value; }
      public String getFilterField() { return filterField; }
      public void setFilterField(String filterField) { this.filterField = filterField; }
      public Object getFilterValue() { return filterValue; }
      public void setFilterValue(Object filterValue) { this.filterValue = filterValue; }
      public long getTimestamp() { return timestamp; }
 }
