package org.rtklib.java.cssr;

import org.rtklib.java.constants.Constants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GridDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    public static class GridPoint {
        public int nid;
        public int gid;
        public double lat;
        public double lon;
        public double alt;

        public GridPoint(int nid, int gid, double lat, double lon, double alt) {
            this.nid = nid;
            this.gid = gid;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
        }
    }

    private List<GridPoint> grid = new ArrayList<>();
    public int inetRef = -1;
    public int ngrid = 0;
    public int[] gridIndex = new int[4];
    public double[] gridWeight = new double[4];

    public void loadFromFile(String filename) throws IOException {
        grid.clear();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line = br.readLine();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 5) continue;
                int nid = Integer.parseInt(parts[0]);
                int gid = Integer.parseInt(parts[1]);
                double lat = Double.parseDouble(parts[2]);
                double lon = Double.parseDouble(parts[3]);
                double alt = Double.parseDouble(parts[4]);
                grid.add(new GridPoint(nid, gid, lat, lon, alt));
            }
        }
    }

    public void addGridPoint(int nid, int gid, double lat, double lon, double alt) {
        grid.add(new GridPoint(nid, gid, lat, lon, alt));
    }

    public List<GridPoint> getGridPoints() {
        return grid;
    }

    public List<GridPoint> getGridByNetwork(int nid) {
        List<GridPoint> result = new ArrayList<>();
        for (GridPoint p : grid) {
            if (p.nid == nid) result.add(p);
        }
        return result;
    }

    public int findGridIndex(double[] pos) {
        double rngMin = 5e3;
        double clat = Math.cos(pos[0]);
        int idxMin = -1;
        double rMin = Double.MAX_VALUE;

        for (int k = 0; k < grid.size(); k++) {
            GridPoint p = grid.get(k);
            double dlat = Math.toRadians(p.lat) - pos[0];
            double dlon = (Math.toRadians(p.lon) - pos[1]) * clat;
            double r = Math.sqrt(dlat * dlat + dlon * dlon) * Constants.RE_WGS84;
            if (r < rMin) {
                rMin = r;
                idxMin = k;
            }
        }

        if (idxMin < 0) {
            inetRef = -1;
            ngrid = 0;
            return -1;
        }

        inetRef = grid.get(idxMin).nid;

        if (rMin < rngMin) {
            ngrid = 1;
            gridIndex[0] = grid.get(idxMin).gid;
            gridWeight[0] = 1.0;
            return inetRef;
        }

        List<GridPoint> netGrid = getGridByNetwork(inetRef);
        double[] rn = new double[netGrid.size()];
        for (int k = 0; k < netGrid.size(); k++) {
            GridPoint p = netGrid.get(k);
            double dlat = Math.toRadians(p.lat) - pos[0];
            double dlon = (Math.toRadians(p.lon) - pos[1]) * clat;
            rn[k] = Math.sqrt(dlat * dlat + dlon * dlon) * Constants.RE_WGS84;
        }

        int n = Math.min(netGrid.size(), 4);
        ngrid = n;
        int[] sortedIdx = argsort(rn);

        for (int k = 0; k < n; k++) {
            gridIndex[k] = netGrid.get(sortedIdx[k]).gid;
        }

        double sumRp = 0;
        for (int k = 0; k < n; k++) {
            gridWeight[k] = 1.0 / rn[sortedIdx[k]];
            sumRp += gridWeight[k];
        }
        for (int k = 0; k < n; k++) {
            gridWeight[k] /= sumRp;
        }

        return inetRef;
    }

    private int[] argsort(double[] arr) {
        int[] idx = new int[arr.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        for (int i = 0; i < idx.length - 1; i++) {
            for (int j = i + 1; j < idx.length; j++) {
                if (arr[idx[j]] < arr[idx[i]]) {
                    int tmp = idx[i];
                    idx[i] = idx[j];
                    idx[j] = tmp;
                }
            }
        }
        return idx;
    }

    public double[] getDpos(double[] pos) {
        List<GridPoint> netGrid = getGridByNetwork(inetRef);
        if (netGrid.isEmpty()) return new double[]{0, 0};
        double posdLat = Math.toDegrees(pos[0]);
        double posdLon = Math.toDegrees(pos[1]);
        double dlat = posdLat - netGrid.get(0).lat;
        double dlon = posdLon - netGrid.get(0).lon;
        return new double[]{dlat, dlon};
    }
}