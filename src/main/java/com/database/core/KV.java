 package com.database.core;
 
 import java.io.Serial;
 import java.io.Serializable;
 import java.util.Objects;
 
 /**
  * 键值对 - 数据库存储的基本单元
  * 支持泛型值，value 可以是数值、字符串、Set、Map 或自定义类型
  */
 public class KV implements Serializable, Comparable<KV> {
     @Serial
     private static final long serialVersionUID = 1L;
     
     private String key;
     private Object value;
     private long timestamp;
     private long version;
     
     public KV(String key, Object value) {
         this.key = key;
         this.value = value;
         this.timestamp = System.currentTimeMillis();
         this.version = 1;
     }
     
     public KV(String key, Object value, long version) {
         this(key, value);
         this.version = version;
     }
     
     public String getKey() { return key; }
     public void setKey(String key) { this.key = key; }
     
     public Object getValue() { return value; }
     public void setValue(Object value) { 
         this.value = value; 
         this.timestamp = System.currentTimeMillis();
         this.version++;
     }
     
     public long getTimestamp() { return timestamp; }
     public long getVersion() { return version; }
     public void setVersion(long version) { this.version = version; }
     
     /**
      * 获取值的字符串表示
      */
     public String getValueAsString() {
         if (value == null) return "null";
         return value.toString();
     }
     
     @Override
     public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof KV kv)) return false;
         return Objects.equals(key, kv.key);
     }
     
     @Override
     public int hashCode() {
         return Objects.hash(key);
     }
     
     @Override
     public int compareTo(KV other) {
         return this.key.compareTo(other.key);
     }
     
     @Override
     public String toString() {
         return "KV{key='" + key + "', value=" + value + ", version=" + version + "}";
     }
 }
