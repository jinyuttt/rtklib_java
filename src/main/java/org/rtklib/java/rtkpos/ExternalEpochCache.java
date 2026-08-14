package org.rtklib.java.rtkpos;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.io.*;
import java.util.*;

/**
 * 外部缓存实现，通过 {@link ExternalCacheProvider} 对接Redis/数据库/文件等。
 * <p>
 * 历元数据通过Java序列化转为byte[]存入外部提供者，
 * 查询时反序列化还原为 {@link CachedEpoch}。
 * </p>
 */
public class ExternalEpochCache implements EpochCache {

    private final ExternalCacheProvider provider;

    public ExternalEpochCache(ExternalCacheProvider provider) {
        this.provider = provider;
    }

    @Override
    public void put(String sourceId, Obsd[] obs, int n, GTime time) {
        byte[] serialized = serialize(new CachedEpoch(obs, n, time));
        if (serialized != null) {
            provider.store(sourceId, time, serialized);
        }
    }

    @Override
    public List<CachedEpoch> query(String sourceId, GTime from, GTime to) {
        List<byte[]> dataList = provider.retrieveRange(sourceId, from, to);
        List<CachedEpoch> result = new ArrayList<>(dataList.size());
        for (byte[] data : dataList) {
            CachedEpoch ce = deserialize(data);
            if (ce != null) result.add(ce);
        }
        return result;
    }

    @Override
    public List<CachedEpoch> getLatest(String sourceId, int count) {
        List<byte[]> dataList = provider.retrieveRange(sourceId, null, null);
        int size = dataList.size();
        int start = Math.max(0, size - count);
        List<CachedEpoch> result = new ArrayList<>();
        for (int i = size - 1; i >= start; i--) {
            CachedEpoch ce = deserialize(dataList.get(i));
            if (ce != null) result.add(ce);
        }
        return result;
    }

    @Override
    public int size(String sourceId) {
        return provider.count(sourceId);
    }

    @Override
    public void clear(String sourceId) {
        provider.deleteAll(sourceId);
    }

    @Override
    public void clearAll() {
        throw new UnsupportedOperationException("clearAll not supported for external cache; use clear(sourceId)");
    }

    private byte[] serialize(CachedEpoch ce) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(ce);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private CachedEpoch deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (CachedEpoch) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}