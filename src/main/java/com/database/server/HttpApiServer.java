package com.database.server;

import com.database.cluster.ClusterManager;
import com.database.common.Protocol;
import com.database.common.Response;
import com.database.core.Database;
import com.database.core.KV;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class HttpApiServer {
    private static final Logger LOG = Logger.getLogger(HttpApiServer.class.getName());
    private final Database database;
    private final ClusterManager clusterManager;
    private final Gson gson;
    private final int port;
    private HttpServer server;

    public HttpApiServer(Database database, ClusterManager clusterManager, int port) {
        this.database = database;
        this.clusterManager = clusterManager;
        this.port = port;
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    public HttpApiServer(Database database, ClusterManager clusterManager) {
        this(database, clusterManager, Protocol.HTTP_PORT);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", new ApiHandler());
        server.start();
        System.out.println("? HTTP RESTful API 服务器已启动，监听端口: " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    private class ApiHandler implements com.sun.net.httpserver.HttpHandler {
        private final Map<String, RouteHandler> routes = new LinkedHashMap<>();

        ApiHandler() {
            register("GET", "/api/databases", this::handleListDatabases);
            register("POST", "/api/databases", this::handleCreateDatabase);
            register("DELETE", "/api/databases/{name}", this::handleDropDatabase);
            register("POST", "/api/databases/{name}/use", this::handleUseDatabase);
            register("GET", "/api/collections", this::handleListCollections);
            register("POST", "/api/collections", this::handleCreateCollection);
            register("DELETE", "/api/collections/{name}", this::handleDropCollection);
            register("GET", "/api/data/{col}", this::handleScan);
            register("GET", "/api/data/{col}/{key}", this::handleGet);
            register("PUT", "/api/data/{col}/{key}", this::handlePut);
            register("POST", "/api/data/{col}/{key}", this::handleUpdate);
            register("DELETE", "/api/data/{col}/{key}", this::handleDelete);
            register("POST", "/api/data/{col}/where", this::handleWhere);
            register("POST", "/api/save", this::handleSave);
            register("POST", "/api/load", this::handleLoad);
            register("POST", "/api/shutdown", this::handleShutdown);
            register("GET", "/api/status", this::handleStatus);
            register("GET", "/api/cluster/status", this::handleClusterStatus);
            register("GET", "/api/help", this::handleHelp);
        }

        private void register(String method, String path, RouteHandler handler) {
            routes.put(method + " " + path, handler);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod().toUpperCase();
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/") && path.length() > 1) {
                    path = path.substring(0, path.length() - 1);
                }

                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

                if ("OPTIONS".equals(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                String requestBody = bodyBytes.length > 0 ? new String(bodyBytes, StandardCharsets.UTF_8) : "";

                Map<String, String> pathParams = new HashMap<>();
                RouteHandler handler = matchRoute(method, path, pathParams);

                if (handler == null) {
                    sendJson(exchange, 200, Map.of("success", false, "message", "路由不存在: " + method + " " + path));
                    return;
                }

                String json = handler.handle(pathParams, requestBody);
                byte[] respBytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            } catch (Exception e) {
                LOG.warning("HTTP 请求处理失败: " + e.getMessage());
                try {
                    String errJson = gson.toJson(Map.of("success", false, "message", "服务器内部错误: " + e.getMessage()));
                    byte[] errBytes = errJson.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(500, errBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(errBytes);
                    }
                } catch (IOException ignored) {}
            }
        }

        private RouteHandler matchRoute(String method, String path, Map<String, String> pathParams) {
            String[] pathSegments = path.split("/");
            for (Map.Entry<String, RouteHandler> entry : routes.entrySet()) {
                String[] parts = entry.getKey().split(" ", 2);
                if (!parts[0].equals(method)) continue;
                String routePattern = parts[1];
                String[] patternSegments = routePattern.split("/");
                if (pathSegments.length != patternSegments.length) continue;
                Map<String, String> params = new HashMap<>();
                boolean match = true;
                for (int i = 0; i < patternSegments.length; i++) {
                    if (patternSegments[i].startsWith("{") && patternSegments[i].endsWith("}")) {
                        String paramName = patternSegments[i].substring(1, patternSegments[i].length() - 1);
                        params.put(paramName, pathSegments[i]);
                    } else if (!patternSegments[i].equals(pathSegments[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    pathParams.putAll(params);
                    return entry.getValue();
                }
            }
            return null;
        }

        private void sendJson(HttpExchange exchange, int code, Object data) throws IOException {
            if (data == null) {
                exchange.sendResponseHeaders(code, -1);
                return;
            }
            String json = gson.toJson(data);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String handleListDatabases(Map<String, String> params, String body) {
            Set<String> dbs = database.listDatabases();
            return gson.toJson(Map.of("success", true, "message", "共 " + dbs.size() + " 个数据库", "data", dbs));
        }

        private String handleCreateDatabase(Map<String, String> params, String body) {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String name = json.get("name").getAsString();
            return gson.toJson(database.createDatabase(name));
        }

        private String handleDropDatabase(Map<String, String> params, String body) {
            return gson.toJson(database.dropDatabase(params.get("name")));
        }

        private String handleUseDatabase(Map<String, String> params, String body) {
            return gson.toJson(database.useDatabase(params.get("name")));
        }

        private String handleListCollections(Map<String, String> params, String body) {
            Set<String> cols = database.listCollections();
            return gson.toJson(Map.of("success", true, "message", "共 " + cols.size() + " 个集合", "data", cols));
        }

        private String handleCreateCollection(Map<String, String> params, String body) {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return gson.toJson(database.createCollection(json.get("name").getAsString()));
        }

        private String handleDropCollection(Map<String, String> params, String body) {
            return gson.toJson(database.dropCollection(params.get("name")));
        }

        private String handleScan(Map<String, String> params, String body) {
            return toJsonResponse(database.scan(params.get("col")));
        }

        private String handleGet(Map<String, String> params, String body) {
            return toJsonResponse(database.get(params.get("col"), params.get("key")));
        }

        private String handlePut(Map<String, String> params, String body) {
            Object value = parseJsonValue(body);
            return toJsonResponse(database.put(params.get("col"), params.get("key"), value));
        }

        private String handleUpdate(Map<String, String> params, String body) {
            Object value = parseJsonValue(body);
            return toJsonResponse(database.update(params.get("col"), params.get("key"), value));
        }

        private String handleDelete(Map<String, String> params, String body) {
            return toJsonResponse(database.delete(params.get("col"), params.get("key")));
        }

        @SuppressWarnings("unchecked")
        private String handleWhere(Map<String, String> params, String body) {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String field = json.get("field").getAsString();
            Object value = gson.fromJson(json.get("value"), Object.class);

            if (json.has("update")) {
                Object updateData = gson.fromJson(json.get("update"), Object.class);
                return toJsonResponse(database.updateWhere(params.get("col"), field, value, updateData));
            } else {
                return toJsonResponse(database.deleteWhere(params.get("col"), field, value));
            }
        }

        private String handleSave(Map<String, String> params, String body) {
            return gson.toJson(database.save());
        }

        private String handleLoad(Map<String, String> params, String body) {
            String name = null;
            if (body != null && !body.isBlank()) {
                try {
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("name")) name = json.get("name").getAsString();
                } catch (Exception ignored) {}
            }
            return gson.toJson(database.load(name));
        }

        private String handleShutdown(Map<String, String> params, String body) {
            new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                database.shutdown();
                System.exit(0);
            }).start();
            return gson.toJson(Map.of("success", true, "message", "服务器正在关闭..."));
        }

        private String handleStatus(Map<String, String> params, String body) {
            Map<String, Object> status = database.getStatus();
            return gson.toJson(Map.of("success", true, "message", "状态信息", "data", status));
        }

        private String handleClusterStatus(Map<String, String> params, String body) {
            if (clusterManager == null || !clusterManager.isClusterEnabled()) {
                return gson.toJson(Map.of("success", false, "message", "集群未启用"));
            }
            return gson.toJson(Map.of("success", true, "message", "集群状态", "data", clusterManager.getClusterStatus()));
        }

        private String handleHelp(Map<String, String> params, String body) {
            Map<String, String> endpoints = new LinkedHashMap<>();
            endpoints.put("GET /api/databases", "列出所有数据库");
            endpoints.put("POST /api/databases", "创建数据库 (body: {\"name\":\"...\"})");
            endpoints.put("DELETE /api/databases/{name}", "删除数据库");
            endpoints.put("POST /api/databases/{name}/use", "切换数据库");
            endpoints.put("GET /api/collections", "列出所有集合");
            endpoints.put("POST /api/collections", "创建集合 (body: {\"name\":\"...\"})");
            endpoints.put("DELETE /api/collections/{name}", "删除集合");
            endpoints.put("GET /api/data/{col}", "扫描集合");
            endpoints.put("GET /api/data/{col}/{key}", "获取文档");
            endpoints.put("PUT /api/data/{col}/{key}", "插入文档 (body: raw value 或 {\"f\":\"v\"})");
            endpoints.put("POST /api/data/{col}/{key}", "更新文档 (body: raw value 或 {\"f\":\"v\"})");
            endpoints.put("DELETE /api/data/{col}/{key}", "删除文档");
            endpoints.put("POST /api/data/{col}/where", "条件删除/更新 (body: {\"field\":\"f\",\"value\":\"v\"[, \"update\":{...}]})");
            endpoints.put("POST /api/save", "持久化保存");
            endpoints.put("POST /api/load", "加载数据 (body: {\"name\":\"...\"})");
            endpoints.put("POST /api/shutdown", "关闭服务器");
            endpoints.put("GET /api/status", "服务器状态");
            endpoints.put("GET /api/cluster/status", "集群状态（集群模式下可用）");
            return gson.toJson(Map.of("success", true, "message", "API 路由列表", "data", endpoints));
        }

        private String toJsonResponse(Response resp) {
            if (resp.getData() instanceof KV kv) {
                Map<String, Object> kvMap = new LinkedHashMap<>();
                kvMap.put("key", kv.getKey());
                kvMap.put("value", kv.getValue());
                kvMap.put("version", kv.getVersion());
                kvMap.put("timestamp", kv.getTimestamp());
                return gson.toJson(Map.of("success", resp.isSuccess(), "message", resp.getMessage(), "data", kvMap));
            }
            if (resp.getData() instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof KV) {
                List<Map<String, Object>> kvList = new ArrayList<>();
                for (Object obj : list) {
                    KV kv = (KV) obj;
                    Map<String, Object> kvMap = new LinkedHashMap<>();
                    kvMap.put("key", kv.getKey());
                    kvMap.put("value", kv.getValue());
                    kvMap.put("version", kv.getVersion());
                    kvMap.put("timestamp", kv.getTimestamp());
                    kvList.add(kvMap);
                }
                return gson.toJson(Map.of("success", resp.isSuccess(), "message", resp.getMessage(), "data", kvList));
            }
            return gson.toJson(resp);
        }

        private Object parseJsonValue(String body) {
            if (body == null || body.isBlank()) return "";
            try {
                return gson.fromJson(body, Object.class);
            } catch (Exception e) {
                return body;
            }
        }
    }

    @FunctionalInterface
    private interface RouteHandler {
        String handle(Map<String, String> pathParams, String requestBody) throws Exception;
    }
}
