package org.rtklib.java.rtkpos;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.io.Serializable;
import java.util.List;

/**
 * 观测历元缓存接口，用于双向滤波。
 * <p>
 * 按数据源标识(sourceId)分桶存储，支持：
 * <ul>
 *   <li>正向：实时存入每个历元</li>
 *   <li>反向：按时间范围查询历史历元，用于回溯重算</li>
 * </ul>
 * </p>
 */
public interface EpochCache {

    /**
     * 存入一个历元。
     *
     * @param sourceId 数据源标识
     * @param obs      观测数据数组
     * @param n        观测数据数量
     * @param time     历元时间
     */
    void put(String sourceId, Obsd[] obs, int n, GTime time);

    /**
     * 查询指定时间范围的历元（按时间升序）。
     *
     * @param sourceId 数据源标识
     * @param from     起始时间（含），可为null表示不限制
     * @param to       结束时间（含），可为null表示不限制
     * @return 匹配的历元列表
     */
    List<CachedEpoch> query(String sourceId, GTime from, GTime to);

    /**
     * 获取指定数据源最新的N个历元（按时间降序，最新在前）。
     *
     * @param sourceId 数据源标识
     * @param count    最大数量
     * @return 历元列表
     */
    List<CachedEpoch> getLatest(String sourceId, int count);

    /**
     * 获取指定数据源的缓存历元数量。
     */
    int size(String sourceId);

    /**
     * 清除指定数据源的缓存。
     */
    void clear(String sourceId);

    /**
     * 清除所有缓存。
     */
    void clearAll();

    /**
     * 缓存的历元数据。
     */
    class CachedEpoch implements Serializable {
        private static final long serialVersionUID = 1L;
        public final Obsd[] obs;
        public final int n;
        public final GTime time;

        public CachedEpoch(Obsd[] obs, int n, GTime time) {
            this.obs = obs;
            this.n = n;
            this.time = time;
        }
    }
}