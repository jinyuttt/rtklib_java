package org.rtklib.java.stream;

/**
 * 串口配置。
 * <p>
 * 封装串口流所需的全部参数，包括端口名称、波特率、数据位、停止位、校验位和超时设置。
 * 所有参数均有默认值，可直接使用或按需调整。
 * </p>
 *
 * <p>默认值一览：
 * <ul>
 *   <li>portName: ""（必须设置，如Windows "COM3"、Linux "/dev/ttyUSB0"）</li>
 *   <li>baudRate: 115200</li>
 *   <li>dataBits: 8</li>
 *   <li>stopBits: 1</li>
 *   <li>parity: 0（无校验）</li>
 *   <li>flowControl: 0（无流控）</li>
 *   <li>readTimeoutMs: 0（非阻塞）</li>
 *   <li>writeTimeoutMs: 0</li>
 * </ul>
 *
 * <p>校验位取值：0=无校验，1=奇校验，2=偶校验，3=Mark，4=Space。
 * <br>流控取值：0=无，1=RTS/CTS硬件流控，2=XON/XOFF软件流控，3=RTS/CTS + XON/XOFF。
 *
 * @see SerialPort
 */
public class SerialPortConfig {

    private String portName = "";
    private int baudRate = 115200;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = 0;
    private int flowControl = 0;
    private int readTimeoutMs = 0;
    private int writeTimeoutMs = 0;

    /** 获取串口名称，如 "COM3" 或 "/dev/ttyUSB0"。 */
    public String getPortName() { return portName; }
    /** 设置串口名称。Windows如"COM3"，Linux如"/dev/ttyUSB0"。 */
    public void setPortName(String portName) { this.portName = portName; }

    /** 获取波特率，默认115200。 */
    public int getBaudRate() { return baudRate; }
    /** 设置波特率，常用值：9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600。 */
    public void setBaudRate(int baudRate) { this.baudRate = baudRate; }

    /** 获取数据位，默认8。 */
    public int getDataBits() { return dataBits; }
    /** 设置数据位，有效值：5, 6, 7, 8。 */
    public void setDataBits(int dataBits) { this.dataBits = dataBits; }

    /** 获取停止位，默认1。 */
    public int getStopBits() { return stopBits; }
    /** 设置停止位，有效值：1, 2。 */
    public void setStopBits(int stopBits) { this.stopBits = stopBits; }

    /** 获取校验位，默认0（无校验）。 */
    public int getParity() { return parity; }
    /** 设置校验位：0=无校验, 1=奇校验, 2=偶校验, 3=Mark, 4=Space。 */
    public void setParity(int parity) { this.parity = parity; }

    /** 获取流控模式，默认0（无流控）。 */
    public int getFlowControl() { return flowControl; }
    /** 设置流控模式：0=无, 1=RTS/CTS, 2=XON/XOFF, 3=RTS/CTS+XON/XOFF。 */
    public void setFlowControl(int flowControl) { this.flowControl = flowControl; }

    /** 获取读取超时时间（毫秒），0表示非阻塞。 */
    public int getReadTimeoutMs() { return readTimeoutMs; }
    /** 设置读取超时时间（毫秒）。 */
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    /** 获取写入超时时间（毫秒），0表示非阻塞。 */
    public int getWriteTimeoutMs() { return writeTimeoutMs; }
    /** 设置写入超时时间（毫秒）。 */
    public void setWriteTimeoutMs(int writeTimeoutMs) { this.writeTimeoutMs = writeTimeoutMs; }

    @Override
    public String toString() {
        return String.format("SerialPortConfig{port='%s', baud=%d, %d%c%d}", portName, baudRate, dataBits, parityChar(), stopBits);
    }

    private char parityChar() {
        return switch (parity) {
            case 1 -> 'O';
            case 2 -> 'E';
            case 3 -> 'M';
            case 4 -> 'S';
            default -> 'N';
        };
    }
}