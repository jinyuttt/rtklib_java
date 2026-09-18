package org.rtklib.java.adjust;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class TestDataConfig {

    private static final String CONFIG_FILE = "test-data.properties";
    private static final String TEMPLATE_FILE = "test-data.properties.template";

    private final Properties props;
    private final boolean available;

    public TestDataConfig() {
        Properties loaded = null;
        boolean ok = false;

        Path localPath = Paths.get("src/test/resources/" + CONFIG_FILE);
        if (Files.exists(localPath)) {
            try (InputStream is = Files.newInputStream(localPath)) {
                loaded = new Properties();
                loaded.load(is);
                ok = true;
            } catch (IOException e) {
                // fall through
            }
        }

        if (!ok) {
            try (InputStream is = TestDataConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (is != null) {
                    loaded = new Properties();
                    loaded.load(is);
                    ok = true;
                }
            } catch (IOException e) {
                // fall through
            }
        }

        this.props = loaded != null ? loaded : new Properties();
        this.available = ok;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getDataRoot() {
        return props.getProperty("data.root", "");
    }

    public String getBaseA() {
        return props.getProperty("base.a", "");
    }

    public String getBaseB() {
        return props.getProperty("base.b", "");
    }

    public String getRover() {
        return props.getProperty("rover", "");
    }

    public String getRover2() {
        return props.getProperty("rover2", "");
    }

    public String getDate() {
        return props.getProperty("date", "");
    }

    public String getRtcmPath(String station, int hour) {
        return String.format("%s\\%s\\%s\\%d.rtcm3", getDataRoot(), station, getDate(), hour);
    }

    public String getMissingMessage() {
        return "测试数据配置文件未找到。请复制 " + TEMPLATE_FILE + " 为 " + CONFIG_FILE +
                " 并填入真实设备ID和数据路径。该文件已加入.gitignore，不会提交到仓库。";
    }
}