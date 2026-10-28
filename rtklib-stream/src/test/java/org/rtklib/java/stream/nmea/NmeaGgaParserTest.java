package org.rtklib.java.stream.nmea;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NmeaGgaParserTest {

    private static final double TOL = 1e-9;

    @Test
    void parseStandardSentence() {
        String line = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";

        Optional<NmeaGga> parsed = NmeaGgaParser.parse(line);
        assertTrue(parsed.isPresent(), "标准语句应解析成功");

        NmeaGga gga = parsed.get();
        assertEquals(12 * 3600 + 35 * 60 + 19, gga.utcSecondsOfDay(), TOL);
        assertEquals(48 + 7.038 / 60.0, gga.latitudeDeg(), TOL);
        assertEquals(11 + 31.0 / 60.0, gga.longitudeDeg(), TOL);
        assertEquals(NmeaGga.FIX_SINGLE, gga.fixQuality());
        assertEquals(8, gga.satellites());
        assertEquals(0.9, gga.hdop(), TOL);
        assertEquals(545.4, gga.mslHeight(), TOL);
        assertEquals(46.9, gga.geoidSeparation(), TOL);
        assertTrue(gga.hasFix());
    }

    @Test
    void ellipsoidHeightAddsGeoidSeparation() {
        NmeaGga gga = NmeaGgaParser.parse(
                "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47").orElseThrow();

        assertEquals(592.3, gga.ellipsoidHeight(), 1e-6);
        double[] pos = gga.toVrsPosition();
        assertEquals(gga.latitudeRad(), pos[0], TOL);
        assertEquals(gga.longitudeRad(), pos[1], TOL);
        assertEquals(gga.ellipsoidHeight(), pos[2], TOL);
    }

    @Test
    void southernWesternHemisphereUsesNegativeSign() {
        String line = withChecksum("$GPGGA,123519,4807.038,S,01131.000,W,1,08,0.9,545.4,M,46.9,M,,");

        NmeaGga gga = NmeaGgaParser.parse(line).orElseThrow();
        assertEquals(-(48 + 7.038 / 60.0), gga.latitudeDeg(), TOL);
        assertEquals(-(11 + 31.0 / 60.0), gga.longitudeDeg(), TOL);
    }

    @Test
    void acceptsAnyTalkerId() {
        String body = "001043.00,4404.14036,N,12118.85961,E,1,12,0.98,111.0,M,46.9,M,,";

        for (String talker : new String[]{"GP", "GN", "GL", "GA", "GB", "BD"}) {
            String line = withChecksum("$" + talker + "GGA," + body);
            assertTrue(NmeaGgaParser.parse(line).isPresent(), "talker " + talker + " 应被接受");
        }
    }

    @Test
    void rejectsWrongChecksum() {
        String line = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48";
        assertFalse(NmeaGgaParser.verifyChecksum(line));
        assertTrue(NmeaGgaParser.parse(line).isEmpty());
    }

    @Test
    void rejectsMissingChecksum() {
        String line = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,";
        assertTrue(NmeaGgaParser.parse(line).isEmpty());
    }

    @Test
    void rejectsNonGgaSentence() {
        String line = withChecksum("$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W");
        assertFalse(NmeaGgaParser.isGga(line));
        assertTrue(NmeaGgaParser.parse(line).isEmpty());
    }

    @Test
    void rejectsTruncatedSentence() {
        String line = withChecksum("$GPGGA,123519,4807.038,N,01131.000,E,1,08");
        assertTrue(NmeaGgaParser.parse(line).isEmpty());
    }

    @Test
    void toleratesMissingOptionalFields() {
        String line = withChecksum("$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,,");

        NmeaGga gga = NmeaGgaParser.parse(line).orElseThrow();
        assertEquals(0.0, gga.geoidSeparation(), TOL);
        assertEquals(545.4, gga.ellipsoidHeight(), 1e-6);
        assertEquals(0.0, gga.differentialAge(), TOL);
        assertEquals(0, gga.referenceStationId());
    }

    @Test
    void invalidFixStillParsesButReportsNoFix() {
        String line = withChecksum("$GPGGA,123519,4807.038,N,01131.000,E,0,00,99.9,545.4,M,46.9,M,,");

        NmeaGga gga = NmeaGgaParser.parse(line).orElseThrow();
        assertEquals(NmeaGga.FIX_INVALID, gga.fixQuality());
        assertFalse(gga.hasFix());
    }

    @Test
    void acceptsLineEnding() {
        String line = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n";
        assertTrue(NmeaGgaParser.parse(line).isPresent());
    }

    private static String withChecksum(String body) {
        int sum = 0;
        for (int i = 1; i < body.length(); i++) {
            sum ^= body.charAt(i);
        }
        return body + "*" + String.format("%02X", sum);
    }
}