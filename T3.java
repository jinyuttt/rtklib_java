import org.rtklib.java.data.Nav;
import org.rtklib.java.ephemeris.OsbReader;
import org.rtklib.java.constants.Constants;
public class T3 {
    public static void main(String[] a) {
        Nav nav = new Nav();
        boolean ok = OsbReader.readOsb("D:\\yaxia\\product\\bia\\WUM0MGXRAP_20261800000_01D_01D_OSB.BIA", nav);
        int f=0,c=0;
        if(nav.fcbWl!=null) for(int i=0;i<Constants.MAXSAT;i++) for(int j=0;j<2;j++) if(nav.fcbWl[i][j]!=0)f++;
        if(nav.cbias!=null) for(int i=0;i<Constants.MAXSAT;i++) for(int j=0;j<nav.cbias[i].length;j++) for(int k=0;k<nav.cbias[i][j].length;k++) if(nav.cbias[i][j][k]!=0)c++;
        System.out.println("RAP: ok="+ok+" fcbWl="+f+" cbias="+c);
        
        Nav nav2 = new Nav();
        boolean ok2 = OsbReader.readOsb("D:\\yaxia\\product\\fcb\\WUM0MGXRTS_20261800000_01D_05M_OSB.BIA", nav2);
        int f2=0,c2=0;
        if(nav2.fcbWl!=null) for(int i=0;i<Constants.MAXSAT;i++) for(int j=0;j<2;j++) if(nav2.fcbWl[i][j]!=0)f2++;
        if(nav2.cbias!=null) for(int i=0;i<Constants.MAXSAT;i++) for(int j=0;j<nav2.cbias[i].length;j++) for(int k=0;k<nav2.cbias[i][j].length;k++) if(nav2.cbias[i][j][k]!=0)c2++;
        System.out.println("RTS: ok="+ok2+" fcbWl="+f2+" cbias="+c2);
    }
}