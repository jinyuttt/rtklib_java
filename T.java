import org.rtklib.java.data.Nav;
import org.rtklib.java.ephemeris.OsbReader;
import org.rtklib.java.constants.Constants;

public class T {
    public static void main(String[] a) {
        Nav nav = new Nav();
        OsbReader.readOsb("D:\\yaxia\\product\\bia\\WUM0MGXRAP_20261800000_01D_01D_OSB.BIA", nav);
        int f=0,c=0;
        if(nav.fcbWl!=null) for(int i=0;i<Constants.MAXSAT;i++) for(int j=0;j<2;j++) if(nav.fcbWl[i][j]!=0)f++;
        if(nav.cbias!=null) for(int i=0;i<Constants.MAXSAT;i++) for(int j=0;j<nav.cbias[i].length;j++) for(int k=0;k<nav.cbias[i][j].length;k++) if(nav.cbias[i][j][k]!=0)c++;
        System.out.println("fcbWl="+f+" cbias="+c);
    }
}