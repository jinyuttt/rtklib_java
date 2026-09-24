import org.rtklib.java.product.ProductDownloader;
import java.io.File;
public class T2 {
    public static void main(String[] a) throws Exception {
        ProductDownloader dl = new ProductDownloader();
        dl.setCacheDir("D:\\yaxia\\product");
        String fcb = dl.downloadFcb(2026, 180);
        System.out.println("FCB result: " + fcb);
        if (fcb != null) {
            File f = new File(fcb);
            System.out.println("File exists: " + f.exists() + " size: " + f.length());
        }
    }
}