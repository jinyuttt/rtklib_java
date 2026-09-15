package org.rtklib.java.rtkpos;

import org.rtklib.java.data.GTime;

import java.util.List;

/**
 * 外部缓存提供者接口，用于对接Redis/数据库/文件等外部存储。
 * <p>
 * 由应用层实现此接口，{@link ExternalEpochCache} 通过此接口存取历元数据。
 * 序列化/反序列化由实现方负责。
 * </p>
 */
public interface ExternalCacheProvider {

    /**
     * 存入一个历元。
     *
     * @param sourceId   数据源标识
     * @param time       历元时间
     * @param serialized 序列化后的历元数据
     */
    void store(String sourceId, GTime time, byte[] serialized);

    /**
     * 取出一个历元。
     *
     * @param sourceId 数据源标识
     * @param time     历元时间
     * @return 序列化数据，不存在则返回null
     */
    byte[] retrieve(String sourceId, GTime time);

    /**
     * 查询时间范围内的历元（按时间升序）。
     *
     * @param sourceId 数据源标识
     * @param from     起始时间（含），可为null
     * @param to       结束时间（含），可为null
     * @return 序列化数据列表
     */
    List<byte[]> retrieveRange(String sourceId, GTime from, GTime to);

    /**
     * 删除指定数据源的所有缓存。
     */
    void deleteAll(String sourceId);

    /**
     * 获取指定数据源的缓存数量。
     */
    int count(String sourceId);
}