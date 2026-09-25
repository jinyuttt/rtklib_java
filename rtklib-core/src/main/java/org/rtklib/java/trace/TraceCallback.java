package org.rtklib.java.trace;

public interface TraceCallback {
    void onTrace(String content);

    default void onLine(String line) {
        onTrace(line);
    }
}