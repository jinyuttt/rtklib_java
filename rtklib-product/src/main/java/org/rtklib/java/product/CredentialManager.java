package org.rtklib.java.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 凭据管理器，从本地配置文件读取敏感信息（如CDDIS用户名密码）。
 * 
 * <p>配置文件位置（按优先级）：
 * <ol>
 *   <li>系统属性：-Drtklib.credentials=/path/to/credentials.properties</li>
 *   <li>环境变量：RTKLIB_CREDENTIALS=/path/to/credentials.properties</li>
 *   <li>用户目录：~/.rtklib/credentials.properties</li>
 *   <li>当前目录：./credentials.properties</li>
 * </ol>
 * 
 * <p>配置文件格式（Java Properties）：
 * <pre>
 * cddis.username=your_username
 * cddis.password=your_password
 * </pre>
 * 
 * <p>安全提示：
 * <ul>
 *   <li>此文件已在.gitignore中，不会提交到Git</li>
 *   <li>建议设置文件权限为600（仅所有者可读写）</li>
 *   <li>不要在其他地方硬编码凭据</li>
 * </ul>
 */
public final class CredentialManager {
    private static final Logger LOG = LoggerFactory.getLogger(CredentialManager.class);
    
    private static final String CDDIS_USERNAME_KEY = "cddis.username";
    private static final String CDDIS_PASSWORD_KEY = "cddis.password";
    
    private static Properties credentials;
    private static boolean loaded = false;
    
    private CredentialManager() {}
    
    /**
     * 加载凭据配置文件。
     */
    private static synchronized void loadCredentials() {
        if (loaded) return;
        
        credentials = new Properties();
        String configPath = null;
        
        // 1. 系统属性
        configPath = System.getProperty("rtklib.credentials");
        
        // 2. 环境变量
        if (configPath == null) {
            configPath = System.getenv("RTKLIB_CREDENTIALS");
        }
        
        // 3. 用户目录
        if (configPath == null) {
            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                File userConfig = new File(userHome, ".rtklib/credentials.properties");
                if (userConfig.exists()) {
                    configPath = userConfig.getAbsolutePath();
                }
            }
        }
        
        // 4. 当前目录
        if (configPath == null) {
            File localConfig = new File("credentials.properties");
            if (localConfig.exists()) {
                configPath = localConfig.getAbsolutePath();
            }
        }
        
        // 加载配置
        if (configPath != null) {
            File configFile = new File(configPath);
            if (configFile.exists() && configFile.canRead()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    credentials.load(fis);
                    LOG.info("Loaded credentials from: {}", configPath);
                } catch (IOException e) {
                    LOG.warn("Failed to load credentials from {}: {}", configPath, e.getMessage());
                }
            } else {
                LOG.debug("Credentials file not found or not readable: {}", configPath);
            }
        } else {
            LOG.debug("No credentials file found. CDDIS downloads will be disabled.");
        }
        
        loaded = true;
    }
    
    /**
     * 获取CDDIS用户名。
     * 
     * @return CDDIS用户名，如果未配置则返回null
     */
    public static String getCddisUsername() {
        loadCredentials();
        return credentials.getProperty(CDDIS_USERNAME_KEY);
    }
    
    /**
     * 获取CDDIS密码。
     * 
     * @return CDDIS密码，如果未配置则返回null
     */
    public static String getCddisPassword() {
        loadCredentials();
        return credentials.getProperty(CDDIS_PASSWORD_KEY);
    }
    
    /**
     * 检查CDDIS凭据是否已配置。
     * 
     * @return 如果用户名和密码都已配置则返回true
     */
    public static boolean hasCddisCredentials() {
        loadCredentials();
        String username = getCddisUsername();
        String password = getCddisPassword();
        return username != null && !username.isEmpty() 
            && password != null && !password.isEmpty();
    }
    
    /**
     * 重新加载凭据（用于测试或配置变更后）。
     */
    public static synchronized void reload() {
        loaded = false;
        credentials = null;
        loadCredentials();
    }
}
