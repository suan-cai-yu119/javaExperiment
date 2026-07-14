 package com.database.core;
 
 import java.io.*;
 import java.util.*;
 import java.util.concurrent.ConcurrentSkipListMap;
 
 /**
  * 集合/表 - 类似于 MySQL 中的表概念
  * 使用 ConcurrentSkipListMap 保证线程安全的有序键值存储
  * 体现了类集框架（ConcurrentSkipListMap）、反射、序列化等知识
  */
 public class Collection_ implements Serializable {
     @Serial
     private static final long serialVersionUID = 1L;
     
     private final String name;
     private final ConcurrentSkipListMap<String, KV> data;
     private long nextVersion;
     private final long createdTime;
     
     public Collection_(String name) {
         this.name = name;
         this.data = new ConcurrentSkipListMap<>();
         this.nextVersion = 1;
         this.createdTime = System.currentTimeMillis();
     }
     
     public String getName() { return name; }
     public int size() { return data.size(); }
     public boolean isEmpty() { return data.isEmpty(); }
     public long getCreatedTime() { return createdTime; }
     
     /**
      * 插入/替换文档（NoSQL upsert 语义）
      * 若 key 已存在，直接替换整个值
      */
     public KV put(String key, Object value) {
         KV kv = new KV(key, value, nextVersion++);
         data.put(key, kv);
         return kv;
     }
     
     /**
      * 获取键对应的值
      */
     public KV get(String key) {
         return data.get(key);
     }
     
     /**
      * 删除键值对
      */
     public KV delete(String key) {
         return data.remove(key);
     }
     
     /**
      * 更新文档字段（NoSQL $set 语义）
      * 若现有值和更新值均为 Map，则合并字段；否则直接替换
      * 替换整个 KV 条目而非原地修改，避免 ObjectOutputStream 缓存问题
      */
     public KV update(String key, Object newValue) {
         KV kv = data.get(key);
         if (kv != null) {
             Object existing = kv.getValue();
             Object finalValue;
             if (existing instanceof Map && newValue instanceof Map) {
                 Map<String, Object> merged = new LinkedHashMap<>();
                 merged.putAll((Map<String, Object>) existing);
                 merged.putAll((Map<String, Object>) newValue);
                 finalValue = merged;
             } else {
                 finalValue = newValue;
             }
             KV newKv = new KV(key, finalValue, nextVersion++);
             data.put(key, newKv);
             return newKv;
         }
         return null;
     }
     
     /**
      * 判断键是否存在
      */
     public boolean containsKey(String key) {
         return data.containsKey(key);
     }
     
     /**
      * 扫描所有键值对
      */
     public List<KV> scan() {
         return new ArrayList<>(data.values());
     }
     
     /**
      * 范围扫描（根据前缀匹配）
      */
     public List<KV> scanByPrefix(String prefix) {
         List<KV> result = new ArrayList<>();
         for (Map.Entry<String, KV> entry : data.tailMap(prefix).entrySet()) {
             if (entry.getKey().startsWith(prefix)) {
                 result.add(entry.getValue());
             } else {
                 break;
             }
         }
         return result;
     }
     
     /**
      * 获取所有键
      */
     public Set<String> keySet() {
         return data.keySet();
     }
     
     /**
      * 清空集合
      */
     public void clear() {
         data.clear();
     }
     
     /**
      * 持久化到文件（使用对象序列化）
      */
     public void saveToFile(String filePath) throws IOException {
         try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filePath))) {
             oos.writeObject(this);
         }
     }
     
     /**
      * 从文件加载（使用对象反序列化）
      */
     public static Collection_ loadFromFile(String filePath) throws IOException, ClassNotFoundException {
         try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filePath))) {
             return (Collection_) ois.readObject();
         }
     }
 }
