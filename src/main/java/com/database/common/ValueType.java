 package com.database.common;
 
 import java.io.Serializable;
 
 /**
  * 值类型枚举 - 支持的数据类型
  */
 public enum ValueType implements Serializable {
     INTEGER("整数"),
     DOUBLE("浮点数"),
     STRING("字符串"),
     SET("集合"),
     MAP("映射"),
     LIST("列表"),
     CUSTOM("自定义类型");
     
     private final String description;
     
     ValueType(String description) {
         this.description = description;
     }
     
     public String getDescription() {
         return description;
     }
     
     /**
      * 根据Java类型推断ValueType
      */
     public static ValueType fromObject(Object obj) {
         if (obj == null) return STRING;
         if (obj instanceof Integer || obj instanceof Long) return INTEGER;
         if (obj instanceof Double || obj instanceof Float) return DOUBLE;
         if (obj instanceof java.util.Set) return SET;
         if (obj instanceof java.util.Map) return MAP;
         if (obj instanceof java.util.List) return LIST;
         return STRING;
     }
 }
