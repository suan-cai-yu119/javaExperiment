package com.database.cluster;

import java.io.Serial;
import java.io.Serializable;

public class ClusterMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Type {
        REGISTER,
        REGISTERED,
        HEARTBEAT,
        WAL_ENTRY,
        WAL_ACK,
        SNAPSHOT_REQ,
        SNAPSHOT_DATA,
        ELECTION,
        NODE_LIST,
    }

    private Type type;
    private Object data;
    private String senderId;
    private long timestamp;

    public ClusterMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public ClusterMessage(Type type, Object data, String senderId) {
        this();
        this.type = type;
        this.data = data;
        this.senderId = senderId;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Object getData() { return data; }
    @SuppressWarnings("unchecked")
    public <T> T getDataAs() { return (T) data; }
    public void setData(Object data) { this.data = data; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public long getTimestamp() { return timestamp; }
}
