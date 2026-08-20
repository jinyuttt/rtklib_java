package org.rtklib.java.ionosphere;

import org.rtklib.java.data.Nav;
import org.rtklib.java.data.SbsMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SBAS消息文件读取器。
 *
 * <p>支持RTKLIB .sbs格式文件，每行格式：</p>
 * <pre>week tow prn type : hex_data</pre>
 * <p>示例：</p>
 * <pre>2300 345600 129  2 : 1C800C3F0000...</pre>
 *
 * <p>读取后自动调用 {@link SbasCorrection#sbsupdatecorr(SbsMsg, Nav)}
 * 更新Nav中的SBAS改正量。</p>
 */
public class SbsMsgReader {

    private static final Logger log = LoggerFactory.getLogger(SbsMsgReader.class);

    public static List<SbsMsg> readsbsmsg(String filePath) {
        return readsbsmsg(filePath, 0);
    }

    public static List<SbsMsg> readsbsmsg(String filePath, int selPrn) {
        List<SbsMsg> msgs = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                SbsMsg msg = parseLine(line);
                if (msg == null) continue;
                if (selPrn != 0 && msg.prn != selPrn) continue;

                msgs.add(msg);
            }
        } catch (IOException e) {
            log.warn("Failed to read SBAS message file: {}", filePath, e);
            return msgs;
        }

        msgs.sort(Comparator.comparingInt((SbsMsg m) -> m.week)
                .thenComparingInt(m -> m.tow)
                .thenComparingInt(m -> m.prn));

        log.info("Read {} SBAS messages from {}", msgs.size(), filePath);
        return msgs;
    }

    public static int applySbsMessages(List<SbsMsg> msgs, Nav nav) {
        if (msgs == null || nav == null) return 0;
        int count = 0;
        for (SbsMsg msg : msgs) {
            int type = SbasCorrection.sbsupdatecorr(msg, nav);
            if (type >= 0) count++;
        }
        log.info("Applied {} SBAS corrections to nav", count);
        return count;
    }

    public static int readsbsmsgAndApply(String filePath, Nav nav) {
        return readsbsmsgAndApply(filePath, nav, 0);
    }

    public static int readsbsmsgAndApply(String filePath, Nav nav, int selPrn) {
        List<SbsMsg> msgs = readsbsmsg(filePath, selPrn);
        return applySbsMessages(msgs, nav);
    }

    public static SbsMsg parseLine(String line) {
        int colonIdx = line.indexOf(':');
        if (colonIdx < 0) return null;

        String header = line.substring(0, colonIdx).trim();
        String hexData = line.substring(colonIdx + 1).trim();

        String[] parts = header.split("\\s+");
        if (parts.length < 3) return null;

        try {
            SbsMsg msg = new SbsMsg();
            msg.week = Integer.parseInt(parts[0]);
            msg.tow = Integer.parseInt(parts[1]);
            msg.prn = Integer.parseInt(parts[2]);

            for (int i = 0; i < 29; i++) msg.msg[i] = 0;
            for (int i = 0; i < hexData.length() / 2 && i < 29; i++) {
                String byteStr = hexData.substring(i * 2, i * 2 + 2);
                msg.msg[i] = (byte) Integer.parseInt(byteStr, 16);
            }
            msg.msg[28] &= (byte) 0xC0;

            return msg;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}