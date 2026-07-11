 package com.database.common;
 
 import java.io.Serial;
 import java.io.Serializable;
 
 /**
  * 响应对象 - 服务器返回给客户端的响应
  * 使用 Serializable 支持网络传输
  */
 public class Response implements Serializable {
     @Serial
     private static final long serialVersionUID = 1L;
     
     private boolean success;
     private String message;
     private Object data;
     private long timestamp;
     
     public Response() {
         this.timestamp = System.currentTimeMillis();
     }
     
     public Response(boolean success, String message) {
         this();
         this.success = success;
         this.message = message;
     }
     
     public Response(boolean success, String message, Object data) {
         this(success, message);
         this.data = data;
     }
     
     public static Response ok(String message) {
         return new Response(true, message);
     }
     
     public static Response ok(String message, Object data) {
         return new Response(true, message, data);
     }
     
     public static Response fail(String message) {
         return new Response(false, message);
     }
     
     // Getters and Setters
     public boolean isSuccess() { return success; }
     public void setSuccess(boolean success) { this.success = success; }
     public String getMessage() { return message; }
     public void setMessage(String message) { this.message = message; }
     public Object getData() { return data; }
     public void setData(Object data) { this.data = data; }
     public long getTimestamp() { return timestamp; }
     
     @Override
     public String toString() {
         return "Response{success=" + success + ", message='" + message + "', data=" + data + "}";
     }
 }
