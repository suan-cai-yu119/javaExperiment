 package com.database;
 
 import com.database.core.*;
 import com.database.common.*;
 import org.junit.jupiter.api.*;
 import static org.junit.jupiter.api.Assertions.*;
 
 import java.util.*;
 import java.util.concurrent.*;
 
 /**
  * JUnit 单元测试
  */
 class DatabaseTests {
     
     private Database db;
     
     @BeforeEach
     void setUp() {
         db = new Database();
         db.createDatabase("testdb");
         db.useDatabase("testdb");
     }
     
     @Test
     @DisplayName("测试数据库创建和切换")
     void testDatabaseOperations() {
         Response r = db.createDatabase("newdb");
         assertTrue(r.isSuccess());
         assertTrue(db.listDatabases().contains("newdb"));
     }
     
     @Test
     @DisplayName("测试集合创建")
     void testCollectionOperations() {
         Response r = db.createCollection("users");
         assertTrue(r.isSuccess());
         assertTrue(db.listCollections().contains("users"));
     }
     
     @Test
     @DisplayName("测试 KV 增删改查")
     void testCRUD() {
         db.createCollection("test");
         
         // Create
         Response r1 = db.put("test", "k1", "value1");
         assertTrue(r1.isSuccess());
         
         // Read
         Response r2 = db.get("test", "k1");
         assertTrue(r2.isSuccess());
         assertEquals("value1", ((KV)r2.getData()).getValue());
         
         // Update
         Response r3 = db.update("test", "k1", "updated");
         assertTrue(r3.isSuccess());
         assertEquals("updated", ((KV)db.get("test", "k1").getData()).getValue());
         
         // Delete
         Response r4 = db.delete("test", "k1");
         assertTrue(r4.isSuccess());
         assertTrue(db.get("test", "k1").isSuccess() == false);
     }
     
     @Test
     @DisplayName("测试多种值类型")
     void testValueTypes() {
         db.createCollection("types");
         
         db.put("types", "str", "Hello 世界");
         assertEquals("Hello 世界", ((KV)db.get("types", "str").getData()).getValue());
         
         db.put("types", "int", 42);
         assertEquals(42, ((KV)db.get("types", "int").getData()).getValue());
         
         db.put("types", "double", 3.14);
         assertEquals(3.14, ((KV)db.get("types", "double").getData()).getValue());
         
         Set<String> set = new HashSet<>(Set.of("a", "b", "c"));
         db.put("types", "set", set);
         assertTrue(((KV)db.get("types", "set").getData()).getValue() instanceof Set);
     }
     
     @Test
     @DisplayName("测试空集合扫描")
     void testEmptyCollection() {
         db.createCollection("empty");
         Response r = db.scan("empty");
         assertTrue(r.isSuccess());
         List<KV> list = (List<KV>) r.getData();
         assertTrue(list.isEmpty());
     }
 }
