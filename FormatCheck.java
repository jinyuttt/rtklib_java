public class FormatCheck {
    public static void main(String[] args) {
        String s = String.format(" %2d %2d %2d %2d %2d%10.3f  %2d%3d",
                26, 6, 7, 17, 0, 18.0, 0, 25);
        System.out.println("[" + s + "]");
        System.out.println("len=" + s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') c = '_';
            System.out.printf("%2d:%s  ", i, c);
            if ((i+1) % 10 == 0) System.out.println();
        }
        System.out.println();
    }
}