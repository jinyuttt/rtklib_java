package org.rtklib.java.stream;

import com.fazecast.jSerialComm.SerialPortDataListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 串口流客户端，基于jSerialComm实现跨平台串口读写。
 * <p>
 * 串口是GNSS接收机最常用的实时数据接入方式。
 * 接收机通过串口（USB转串口或原生串口）输出RTCM3、UBX、NovAtel等格式的原始观测数据，
 * 本类负责打开串口、异步读取数据并通过 {@link StreamListener} 回调推送。
 * </p>
 *
 * <p>支持特性：
 * <ul>
 *   <li>跨平台串口访问（Windows COM口、Linux /dev/ttyUSB*、macOS /dev/cu.*）</li>
 *   <li>可配置波特率、数据位、停止位、校验位、流控</li>
 *   <li>异步数据读取，数据到达时通过监听器回调</li>
 *   <li>同步/异步写入（向接收机发送命令）</li>
 *   <li>列举系统可用串口</li>
 * </ul>
 *
 * <p>典型使用流程：
 * <pre>
 *   // 1. 创建配置
 *   SerialPortConfig cfg = new SerialPortConfig();
 *   cfg.setPortName("COM3");
 *   cfg.setBaudRate(115200);
 *
 *   // 2. 创建串口客户端
 *   SerialPort port = new SerialPort(cfg, new StreamListener() {
 *       public void onData(byte[] data, int offset, int length) {
 *           // 处理收到的接收机数据
 *       }
 *       public void onError(Exception e) { e.printStackTrace(); }
 *       public void onClosed() { System.out.println("closed"); }
 *   });
 *
 *   // 3. 打开并启动
 *   port.open();
 *   port.start();
 *
 *   // 4. 使用完毕后关闭
 *   port.stop();
 *   port.close();
 * </pre>
 *
 * @see SerialPortConfig
 * @see StreamListener
 */
public class SerialPort {

    private static final Logger log = LoggerFactory.getLogger(SerialPort.class);

    private final SerialPortConfig config;
    private final StreamListener listener;

    private com.fazecast.jSerialComm.SerialPort commPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean opened = new AtomicBoolean(false);

    /**
     * 使用配置对象创建串口客户端。
     *
     * @param config   串口配置，不可为null
     * @param listener 数据监听器，不可为null
     */
    public SerialPort(SerialPortConfig config, StreamListener listener) {
        this.config = config;
        this.listener = listener;
    }

    /**
     * 使用独立参数创建串口客户端（便捷构造器）。
     *
     * @param portName 串口名称，如 "COM3" 或 "/dev/ttyUSB0"
     * @param baudRate 波特率
     * @param listener 数据监听器
     */
    public SerialPort(String portName, int baudRate, StreamListener listener) {
        SerialPortConfig cfg = new SerialPortConfig();
        cfg.setPortName(portName);
        cfg.setBaudRate(baudRate);
        this.config = cfg;
        this.listener = listener;
    }

    /**
     * 打开串口并应用配置参数。
     * <p>
     * 设置波特率、数据位、停止位、校验位、流控模式和超时。
     * 打开成功后可调用 {@link #start()} 启动异步数据读取。
     * </p>
     *
     * @throws IllegalStateException 串口不存在或已被占用
     */
    public void open() {
        commPort = com.fazecast.jSerialComm.SerialPort.getCommPort(config.getPortName());
        if (!commPort.openPort()) {
            throw new IllegalStateException("Failed to open serial port: " + config.getPortName());
        }

        commPort.setBaudRate(config.getBaudRate());
        commPort.setNumDataBits(config.getDataBits());
        commPort.setNumStopBits(config.getStopBits());
        commPort.setParity(mapParity(config.getParity()));
        commPort.setFlowControl(mapFlowControl(config.getFlowControl()));
        commPort.setComPortTimeouts(
                com.fazecast.jSerialComm.SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                config.getReadTimeoutMs(),
                config.getWriteTimeoutMs()
        );

        opened.set(true);
        log.info("Serial port opened: {} @ {} bps", config.getPortName(), config.getBaudRate());
    }

    /**
     * 启动异步数据读取。
     * <p>
     * 调用前必须先成功调用 {@link #open()}。
     * 使用jSerialComm的事件驱动模式，数据到达时通过 {@link StreamListener#onData} 回调。
     * </p>
     *
     * @throws IllegalStateException 串口未打开时调用
     */
    public void start() {
        if (!opened.get()) {
            throw new IllegalStateException("Serial port not opened. Call open() first.");
        }
        running.set(true);
        commPort.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return com.fazecast.jSerialComm.SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }

            @Override
            public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent event) {
                if (event.getEventType() != com.fazecast.jSerialComm.SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                    return;
                }
                int avail = commPort.bytesAvailable();
                if (avail <= 0) return;
                byte[] buf = new byte[avail];
                int n = commPort.readBytes(buf, avail);
                if (n > 0 && listener != null) {
                    listener.onData(buf, 0, n);
                }
            }
        });
        log.info("Serial port reading started: {}", config.getPortName());
    }

    /**
     * 停止异步数据读取。
     * <p>
     * 移除数据监听器，停止回调推送。串口保持打开状态，可再次调用 {@link #start()}。
     * </p>
     */
    public void stop() {
        running.set(false);
        if (commPort != null && commPort.isOpen()) {
            commPort.removeDataListener();
        }
        log.info("Serial port reading stopped: {}", config.getPortName());
    }

    /**
     * 关闭串口并释放资源。
     * <p>
     * 关闭后回调 {@link StreamListener#onClosed()}。
     * 关闭后不可再调用 {@link #start()}，需重新 {@link #open()}。
     * </p>
     */
    public void close() {
        running.set(false);
        if (commPort != null && commPort.isOpen()) {
            commPort.removeDataListener();
            commPort.closePort();
        }
        opened.set(false);
        if (listener != null) {
            listener.onClosed();
        }
        log.info("Serial port closed: {}", config.getPortName());
    }

    /**
     * 向串口写入数据（同步阻塞）。
     * <p>
     * 用于向GNSS接收机发送配置命令或NMEA请求。
     * </p>
     *
     * @param data 待写入的数据
     * @return 实际写入的字节数，-1表示失败
     */
    public int write(byte[] data) {
        if (commPort != null && opened.get()) {
            return commPort.writeBytes(data, data.length);
        }
        return -1;
    }

    /**
     * 向串口写入字符串（UTF-8编码，同步阻塞）。
     * <p>
     * 便捷方法，常用于发送NMEA或接收机配置命令。
     * </p>
     *
     * @param text 待写入的文本
     * @return 实际写入的字节数，-1表示失败
     */
    public int writeString(String text) {
        return write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 异步读取是否正在运行。 */
    public boolean isRunning() { return running.get(); }

    /** 串口是否已打开。 */
    public boolean isOpened() { return opened.get(); }

    /**
     * 获取串口可读取的字节数。
     *
     * @return 缓冲区中可读取的字节数，串口未打开时返回0
     */
    public int bytesAvailable() {
        if (commPort != null && opened.get()) {
            return commPort.bytesAvailable();
        }
        return 0;
    }

    /**
     * 列举系统所有可用串口。
     *
     * @return 串口描述数组，每个元素包含端口名称和描述信息
     */
    public static SerialPortInfo[] listPorts() {
        com.fazecast.jSerialComm.SerialPort[] ports = com.fazecast.jSerialComm.SerialPort.getCommPorts();
        SerialPortInfo[] result = new SerialPortInfo[ports.length];
        for (int i = 0; i < ports.length; i++) {
            result[i] = new SerialPortInfo(ports[i].getSystemPortName(), ports[i].getDescriptivePortName());
        }
        return result;
    }

    /**
     * 检查指定名称的串口是否存在。
     *
     * @param portName 串口名称
     * @return 是否存在
     */
    public static boolean portExists(String portName) {
        com.fazecast.jSerialComm.SerialPort[] ports = com.fazecast.jSerialComm.SerialPort.getCommPorts();
        for (com.fazecast.jSerialComm.SerialPort p : ports) {
            if (p.getSystemPortName().equals(portName)) return true;
        }
        return false;
    }

    /**
     * 将配置中的校验位常量映射为jSerialComm校验位常量。
     */
    private static int mapParity(int parity) {
        return switch (parity) {
            case 1 -> com.fazecast.jSerialComm.SerialPort.ODD_PARITY;
            case 2 -> com.fazecast.jSerialComm.SerialPort.EVEN_PARITY;
            case 3 -> com.fazecast.jSerialComm.SerialPort.MARK_PARITY;
            case 4 -> com.fazecast.jSerialComm.SerialPort.SPACE_PARITY;
            default -> com.fazecast.jSerialComm.SerialPort.NO_PARITY;
        };
    }

    /**
     * 将配置中的流控常量映射为jSerialComm流控常量。
     */
    private static int mapFlowControl(int flowControl) {
        return switch (flowControl) {
            case 1 -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_RTS_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case 2 -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            case 3 -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_RTS_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_CTS_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            default -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_DISABLED;
        };
    }

    /**
     * 串口描述信息。
     */
    public static class SerialPortInfo {
        /** 系统端口名称，如 "COM3" 或 "/dev/ttyUSB0"。 */
        public final String systemName;
        /** 可读描述名称，如 "USB Serial Device (COM3)"。 */
        public final String description;

        public SerialPortInfo(String systemName, String description) {
            this.systemName = systemName;
            this.description = description;
        }

        @Override
        public String toString() {
            return systemName + " - " + description;
        }
    }
}