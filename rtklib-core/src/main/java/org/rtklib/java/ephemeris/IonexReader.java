package org.rtklib.java.ephemeris;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * IONEX电离层网格产品读取器。
 *
 * <p>读取IGS IONEX格式的全球电离层图(GIM)产品，用于单频PPP电离层约束或区域增强。
 *
 * <p>文件格式：IGS IONEX v1.0/v2.0
 * <pre>
 *     1.0            IONEX VERSION / TYPE
 *     ...            EPOCH OF FIRST MAP
 *     ...            EPOCH OF LAST MAP
 *     90.0 -90.0   2.5                 LAT1 / LAT2 / DLAT
 *    -180.0 180.0   5.0                LON1 / LON2 / DLON
 *     450.0 450.0   0.0                HGT1 / HGT2 / DHGT
 *     ...            START OF TEC MAP
 *     ...            EPOCH OF CURRENT MAP
 *     ...            TEC grid values
 *     ...            END OF TEC MAP
 * </pre>
 *
 * <p>TEC单位：TECU (1 TECU = 10^16 electrons/m²)
 * <p>对应C版：RTKLIB的readtec()，本模块为新增功能
 */
public final class IonexReader {
    private IonexReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(IonexReader.class);

    private static final int MAX_MAPS = 25;

    public static boolean readIonex(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;

        IonexHeader hdr = new IonexHeader();
        List<IonexMap> maps = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean inHeader = true;

            while ((line = br.readLine()) != null) {
                if (line.length() < 60) continue;
                String label = line.substring(60).trim();

                if (inHeader) {
                    if (label.equals("IONEX VERSION / TYPE")) {
                        hdr.version = str2num(line, 2, 8);
                    } else if (label.equals("LAT1 / LAT2 / DLAT")) {
                        hdr.lat1 = str2num(line, 2, 6);
                        hdr.lat2 = str2num(line, 8, 6);
                        hdr.dlat = str2num(line, 14, 5);
                    } else if (label.equals("LON1 / LON2 / DLON")) {
                        hdr.lon1 = str2num(line, 2, 6);
                        hdr.lon2 = str2num(line, 8, 6);
                        hdr.dlon = str2num(line, 14, 5);
                    } else if (label.equals("HGT1 / HGT2 / DHGT")) {
                        hdr.hgt1 = str2num(line, 2, 6);
                        hdr.hgt2 = str2num(line, 8, 6);
                        hdr.dhgt = str2num(line, 14, 5);
                    } else if (label.equals("EXPONENT")) {
                        hdr.exponent = (int) str2num(line, 2, 6);
                    } else if (label.equals("END OF HEADER")) {
                        inHeader = false;
                    }
                } else {
                    if (label.equals("START OF TEC MAP")) {
                        IonexMap map = readTecMap(br, hdr);
                        if (map != null) {
                            maps.add(map);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to read IONEX file: {}", file, e);
            return false;
        }

        if (maps.isEmpty()) {
            LOG.warn("No TEC maps found in IONEX file: {}", file);
            return false;
        }

        if (nav.ionexGrid == null) {
            nav.ionexGrid = new IonexGrid();
        }
        nav.ionexGrid.header = hdr;
        nav.ionexGrid.maps = maps.toArray(new IonexMap[0]);

        LOG.info("IONEX: read {} TEC maps from {}", maps.size(), file);
        return true;
    }

    private static IonexMap readTecMap(BufferedReader br, IonexHeader hdr) throws IOException {
        IonexMap map = new IonexMap();
        map.tec = new double[hdr.nLat()][hdr.nLon()];

        String line;
        while ((line = br.readLine()) != null) {
            if (line.length() < 60) continue;
            String label = line.substring(60).trim();

            if (label.equals("EPOCH OF CURRENT MAP")) {
                double[] ep = new double[6];
                ep[0] = str2num(line, 2, 4);
                ep[1] = str2num(line, 7, 2);
                ep[2] = str2num(line, 10, 2);
                ep[3] = str2num(line, 13, 2);
                ep[4] = str2num(line, 16, 2);
                ep[5] = str2num(line, 19, 2);
                map.time = TimeSystem.epoch2time(ep);
            } else if (label.equals("LAT/LON1/LON2/DLON/HGT")) {
                double lat = str2num(line, 2, 6);
                double lon1 = str2num(line, 8, 6);
                double lon2 = str2num(line, 14, 6);
                double dlon = str2num(line, 20, 5);

                int latIdx = hdr.latIndex(lat);
                if (latIdx < 0 || latIdx >= hdr.nLat()) continue;

                readTecRow(br, map.tec[latIdx], lon1, lon2, dlon, hdr);
            } else if (label.equals("END OF TEC MAP")) {
                break;
            }
        }

        double scale = Math.pow(10.0, hdr.exponent);
        for (int i = 0; i < map.tec.length; i++) {
            for (int j = 0; j < map.tec[i].length; j++) {
                map.tec[i][j] *= scale;
            }
        }

        return map;
    }

    private static void readTecRow(BufferedReader br, double[] row, double lon1, double lon2,
                                    double dlon, IonexHeader hdr) throws IOException {
        int nLon = hdr.nLon();
        int count = 0;
        String line;

        while (count < nLon && (line = br.readLine()) != null) {
            String[] tokens = line.trim().split("\\s+");
            for (String token : tokens) {
                if (count >= nLon) break;
                try {
                    row[count] = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    row[count] = 0.0;
                }
                count++;
            }
        }
    }

    private static double str2num(String line, int pos, int len) {
        if (line.length() < pos + len) return 0.0;
        try {
            return Double.parseDouble(line.substring(pos - 1, pos - 1 + len).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static class IonexHeader {
        public double version = 1.0;
        public double lat1 = 90.0, lat2 = -90.0, dlat = 2.5;
        public double lon1 = -180.0, lon2 = 180.0, dlon = 5.0;
        public double hgt1 = 450.0, hgt2 = 450.0, dhgt = 0.0;
        public int exponent = -1;

        public int nLat() {
            return (int) Math.round(Math.abs(lat2 - lat1) / Math.abs(dlat)) + 1;
        }

        public int nLon() {
            return (int) Math.round(Math.abs(lon2 - lon1) / Math.abs(dlon)) + 1;
        }

        public int latIndex(double lat) {
            return (int) Math.round((lat - lat1) / dlat);
        }

        public int lonIndex(double lon) {
            return (int) Math.round((lon - lon1) / dlon);
        }
    }

    public static class IonexMap {
        public GTime time;
        public double[][] tec;
    }

    public static class IonexGrid {
        public IonexHeader header;
        public IonexMap[] maps;

        public double interpolate(GTime time, double lat, double lon) {
            if (maps == null || maps.length == 0) return 0.0;

            int idx1 = -1, idx2 = -1;
            double ratio = 0.0;

            for (int i = 0; i < maps.length - 1; i++) {
                if (maps[i].time.compareTo(time) <= 0 && maps[i + 1].time.compareTo(time) >= 0) {
                    idx1 = i;
                    idx2 = i + 1;
                    double dt = maps[i + 1].time.time - maps[i].time.time;
                    ratio = dt > 0 ? (time.time - maps[i].time.time) / dt : 0.0;
                    break;
                }
            }

            if (idx1 < 0) {
                if (time.compareTo(maps[0].time) < 0) return interpGrid(maps[0], header, lat, lon);
                return interpGrid(maps[maps.length - 1], header, lat, lon);
            }

            double tec1 = interpGrid(maps[idx1], header, lat, lon);
            double tec2 = interpGrid(maps[idx2], header, lat, lon);
            return tec1 + ratio * (tec2 - tec1);
        }

        private static double interpGrid(IonexMap map, IonexHeader hdr, double lat, double lon) {
            double latF = (lat - hdr.lat1) / hdr.dlat;
            double lonF = (lon - hdr.lon1) / hdr.dlon;

            int latIdx = (int) Math.floor(latF);
            int lonIdx = (int) Math.floor(lonF);

            latIdx = Math.max(0, Math.min(latIdx, hdr.nLat() - 2));
            lonIdx = Math.max(0, Math.min(lonIdx, hdr.nLon() - 2));

            double dLat = latF - latIdx;
            double dLon = lonF - lonIdx;

            double v00 = map.tec[latIdx][lonIdx];
            double v01 = map.tec[latIdx][lonIdx + 1];
            double v10 = map.tec[latIdx + 1][lonIdx];
            double v11 = map.tec[latIdx + 1][lonIdx + 1];

            return (1 - dLat) * (1 - dLon) * v00 +
                   (1 - dLat) * dLon * v01 +
                   dLat * (1 - dLon) * v10 +
                   dLat * dLon * v11;
        }
    }
}
