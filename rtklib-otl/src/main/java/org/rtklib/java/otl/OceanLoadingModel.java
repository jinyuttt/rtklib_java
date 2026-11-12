package org.rtklib.java.otl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * [JAVA-ONLY 原创] 海潮负荷模型的数据集合：FES2004 球谐系数与负荷 Love 数。
 *
 * <p><b>来源说明</b>：FES2004 球谐系数来自 LEGOS/CNES 公开发布的模型产品，
 * 负荷 Love 数来自公开发表的负荷 Love 数表。本类只负责按分潮归类与只读访问，
 * 属数据组织逻辑，不含任何第三方源码结构。</p>
 *
 * <p>本类是不可变值对象。</p>
 */
public final class OceanLoadingModel {

    private final List<Fes2004Record> allRecords;
    private final Map<TidalConstituent, List<Fes2004Record>> recordsByConstituent;
    private final LoveNumberLoader.LoveNumbers loveNumbers;

    private OceanLoadingModel(List<Fes2004Record> allRecords,
                              Map<TidalConstituent, List<Fes2004Record>> recordsByConstituent,
                              LoveNumberLoader.LoveNumbers loveNumbers) {
        this.allRecords = allRecords;
        this.recordsByConstituent = recordsByConstituent;
        this.loveNumbers = loveNumbers;
    }

    /** 从内置资源加载 FES2004 与 Love 数。 */
    public static OceanLoadingModel load() throws IOException {
        List<Fes2004Record> records = Fes2004Loader.load();
        LoveNumberLoader.LoveNumbers loveNumbers = LoveNumberLoader.load(BlqConsts.MAX_DEG);

        Map<TidalConstituent, List<Fes2004Record>> grouped = new EnumMap<>(TidalConstituent.class);
        for (TidalConstituent constituent : TidalConstituent.values()) {
            grouped.put(constituent, new ArrayList<>());
        }
        for (Fes2004Record record : records) {
            for (TidalConstituent constituent : TidalConstituent.values()) {
                if (constituent.matches(record.doodson)) {
                    grouped.get(constituent).add(record);
                    break;
                }
            }
        }
        return new OceanLoadingModel(records, grouped, loveNumbers);
    }

    /** 全部模型记录，保持文件原始顺序。 */
    public List<Fes2004Record> allRecords() {
        return allRecords;
    }

    /** 指定分潮的模型记录。 */
    public List<Fes2004Record> recordsFor(TidalConstituent constituent) {
        return recordsByConstituent.get(constituent);
    }

    /** 判断模型记录所属的分潮。 */
    public TidalConstituent constituentOf(Fes2004Record record) {
        for (TidalConstituent constituent : TidalConstituent.values()) {
            if (constituent.matches(record.doodson)) {
                return constituent;
            }
        }
        throw new IllegalArgumentException("模型记录不属于任何 BLQ 分潮：" + record.doodson);
    }

    public LoveNumberLoader.LoveNumbers loveNumbers() {
        return loveNumbers;
    }

    /** 第 {@code degree} 阶的负荷 Love 数 h。 */
    public double loveH(int degree) {
        return loveNumbers.h[degree];
    }

    /** 第 {@code degree} 阶的负荷 Love 数 l。 */
    public double loveL(int degree) {
        return loveNumbers.l[degree];
    }
}