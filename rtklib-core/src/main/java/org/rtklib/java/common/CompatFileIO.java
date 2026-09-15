package org.rtklib.java.common;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 兼容 Android API < 26 的文件 I/O 工具类。
 * 替代 java.nio.file.Files/Paths 和 InputStream.readAllBytes() 等 Java 7+/9+ API。
 */
public final class CompatFileIO {

    private CompatFileIO() {
    }

    /**
     * 读取文件全部内容为字节数组。
     * 兼容替代 Files.readAllBytes(Paths.get(path))。
     *
     * @param filePath 文件路径
     * @return 文件内容字节数组
     * @throws IOException 读取失败
     */
    public static byte[] readAllBytes(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 创建目录（含父目录）。
     * 兼容替代 Files.createDirectories(Path.of(dir))。
     *
     * @param dir 目录路径
     * @return 目录是否已存在或创建成功
     */
    public static boolean createDirectories(String dir) {
        File file = new File(dir);
        return file.exists() || file.mkdirs();
    }

    /**
     * 拼接文件路径。
     * 兼容替代 Paths.get(base, name).toString()。
     *
     * @param base 基础路径
     * @param name 文件名
     * @return 拼接后的路径
     */
    public static String joinPath(String base, String name) {
        return new File(base, name).getPath();
    }
}