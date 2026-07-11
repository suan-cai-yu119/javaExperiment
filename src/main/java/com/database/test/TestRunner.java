 package com.database.test;
 
 import com.database.core.*;
 import com.database.common.*;
 import com.database.server.*;
 import com.database.client.*;
 
 import java.io.*;
 import java.util.*;
 import java.util.concurrent.*;
 
 /**
  * 测试运行器 - 对数据库功能进行自动测试
  * 体现：单元测试思想、多线程测试
  */
 public class TestRunner {
     
     private static int passed = 0;
     private static int failed = 0;
     
     public static void main(String[] args) {
         System.out.println("╔═══════════════════════════════════════════╗");
         System.out.println("║      迷你数据库系统 - 功能测试            ║");
         System.out.println("╚═══════════════════════════════════════════╝\n");
         
         // 测试核心功能
         testKV();
         testCollection();
         testDatabase();
         testSerialization();
         testMultiThreading();
         testValueTypes();
         
         System.out.println("\n╔═══════════════════════════════════════════╗");
         System.out.printf("║  测试结果: 通过 %d / 失败 %d / 总计 %d    ║%n", 
             passed, failed, passed + failed);
         System.out.println("╚═══════════════════════════════════════════╝");
         
         System.exit(failed > 0 ? 1 : 0);
     }
     
     static void assertEq(Object expected, Object actual, String testName) {
         if (Objects.equals(expected, actual)) {
             passed++;
             System.out.println("  ✓ " + testName);
         } else {
             failed++;
             System.out.println("  ✗ " + testName + " (期望: " + expected + ", 实际: " + actual + ")");
         }
     }
     
     static void assertTrue(boolean condition, String testName) {
         if (condition) {
             passed++;
             System.out.println("  ✓ " + testName);
         } else {
             failed++;
             System.out.println("  ✗ " + testName);
         }
     }
     
     // 1. KV 测试
     static void testKV() {
         System.out.println("\n--- 测试: KV 键值对 ---");
         KV kv = new KV("name", "张三");
         assertEq("name", kv.getKey(), "KV 键应为 'name'");
         assertEq("张三", kv.getValue(), "KV 值应为 '张三'");
         assertEq(1L, kv.getVersion(), "初始版本应为 1");
         
         kv.setValue("李四");
         assertEq("李四", kv.getValue(), "更新后的值应为 '李四'");
         assertEq(2L, kv.getVersion(), "更新后版本应为 2");
     }
     
     // 2. Collection 测试
     static void testCollection() {
         System.out.println("\n--- 测试: Collection 集合 ---");
         Collection_ col = new Collection_("users");
         
         assertEq("users", col.getName(), "集合名称应为 'users'");
         assertTrue(col.isEmpty(), "新集合应为空");
         
         col.put("user1", "张三");
         col.put("user2", "李四");
         col.put("user3", "王五");
         
         assertEq(3, col.size(), "插入3条记录后大小应为 3");
         assertTrue(col.containsKey("user1"), "应包含 user1");
         assertEq("李四", col.get("user2").getValue(), "user2 的值应为 '李四'");
         
         col.update("user1", "张三(已更新)");
         assertEq("张三(已更新)", col.get("user1").getValue(), "更新后 user1 的值");
         
         col.delete("user3");
         assertEq(2, col.size(), "删除后大小应为 2");
         assertTrue(!col.containsKey("user3"), "不应再包含 user3");
     }
     
     // 3. Database 测试
     static void testDatabase() {
         System.out.println("\n--- 测试: Database 引擎 ---");
         Database db = new Database();
         
         Response r1 = db.createDatabase("testdb");
         assertTrue(r1.isSuccess(), "创建数据库应成功");
         
         Response r2 = db.useDatabase("testdb");
         assertTrue(r2.isSuccess(), "切换数据库应成功");
         
         Response r3 = db.createCollection("students");
         assertTrue(r3.isSuccess(), "创建集合应成功");
         
         Response r4 = db.put("students", "s001", "小明");
         assertTrue(r4.isSuccess(), "插入数据应成功");
         
         Response r5 = db.get("students", "s001");
         assertTrue(r5.isSuccess(), "查询数据应成功");
         if (r5.getData() instanceof KV kv) {
             assertEq("小明", kv.getValue(), "查询结果应为 '小明'");
         }
         
         Response r6 = db.put("students", "s002", 100);
         assertTrue(r6.isSuccess(), "插入整数值应成功");
         
         Response r7 = db.update("students", "s001", "大明");
         assertTrue(r7.isSuccess(), "更新数据应成功");
         
         Response r8 = db.scan("students");
         assertTrue(r8.isSuccess(), "扫描应成功");
         assertTrue(r8.getData() instanceof List, "扫描结果应为列表");
         
         assertTrue(db.listDatabases().contains("testdb"), "数据库列表应包含 testdb");
     }
     
     // 4. 序列化测试
     static void testSerialization() {
         System.out.println("\n--- 测试: 序列化/反序列化 ---");
         try {
             Collection_ col = new Collection_("test_serial");
             col.put("k1", "value1");
             col.put("k2", new HashMap<>(Map.of("a", 1, "b", 2)));
             
             String tempFile = "data/test_serial.tmp";
             new File("data").mkdirs();
             
             col.saveToFile(tempFile);
             assertTrue(new File(tempFile).exists(), "序列化文件应存在");
             
             Collection_ loaded = Collection_.loadFromFile(tempFile);
             assertEq(2, loaded.size(), "反序列化后大小应为 2");
             assertEq("value1", loaded.get("k1").getValue(), "反序列化后 k1 的值应正确");
             
             new File(tempFile).delete();
             System.out.println("  ✓ 序列化/反序列化测试通过");
         } catch (Exception e) {
             failed++;
             System.out.println("  ✗ 序列化测试异常: " + e.getMessage());
         }
     }
     
     // 5. 多线程测试
     static void testMultiThreading() {
         System.out.println("\n--- 测试: 多线程并发 ---");
         try {
             Database db = new Database();
             db.createDatabase("concurrent");
             db.useDatabase("concurrent");
             db.createCollection("threads");
             
             int threadCount = 10;
             int opsPerThread = 100;
             ExecutorService executor = Executors.newFixedThreadPool(threadCount);
             CountDownLatch latch = new CountDownLatch(threadCount);
             
             for (int i = 0; i < threadCount; i++) {
                 final int threadId = i;
                 executor.submit(() -> {
                     try {
                         for (int j = 0; j < opsPerThread; j++) {
                             db.put("threads", "key-" + threadId + "-" + j, 
                                 "value-" + threadId + "-" + j);
                         }
                     } finally {
                         latch.countDown();
                     }
                 });
             }
             
             boolean completed = latch.await(30, TimeUnit.SECONDS);
             executor.shutdown();
             
             assertTrue(completed, "多线程写入应在30秒内完成");
             assertEq(threadCount * opsPerThread, 
                 db.scan("threads").getData() instanceof List<?> list ? list.size() : 0, 
                 "多线程写入总数应正确");
             
             System.out.println("  ✓ 多线程压力测试通过 (" + (threadCount * opsPerThread) + " 次操作)");
         } catch (Exception e) {
             failed++;
             System.out.println("  ✗ 多线程测试异常: " + e.getMessage());
         }
     }
     
     // 6. 支持多种值类型测试
     static void testValueTypes() {
         System.out.println("\n--- 测试: 多种值类型支持 ---");
         Database db = new Database();
         db.createDatabase("types");
         db.useDatabase("types");
         db.createCollection("test_types");
         
         // 字符串
         db.put("test_types", "str", "Hello World");
         assertEq("Hello World", ((KV)db.get("test_types", "str").getData()).getValue(), "字符串类型");
         
         // 整数
         db.put("test_types", "int", 42);
         assertEq(42, ((KV)db.get("test_types", "int").getData()).getValue(), "整数类型");
         
         // 浮点数
         db.put("test_types", "double", 3.14);
         assertEq(3.14, ((KV)db.get("test_types", "double").getData()).getValue(), "浮点数类型");
         
         // 集合 (Set)
         Set<String> set = new HashSet<>(Set.of("a", "b", "c"));
         db.put("test_types", "set", set);
         assertTrue(((KV)db.get("test_types", "set").getData()).getValue() instanceof Set, "集合类型");
         
         // 映射 (Map)
         Map<String, Object> map = new HashMap<>();
         map.put("name", "测试");
         map.put("age", 20);
         db.put("test_types", "map", map);
         assertTrue(((KV)db.get("test_types", "map").getData()).getValue() instanceof Map, "映射类型");
         
         // 列表 (List)
         List<String> list = List.of("x", "y", "z");
         db.put("test_types", "list", list);
         assertTrue(((KV)db.get("test_types", "list").getData()).getValue() instanceof List, "列表类型");
     }
 }
