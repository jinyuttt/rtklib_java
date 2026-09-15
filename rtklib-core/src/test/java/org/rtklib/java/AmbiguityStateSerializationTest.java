package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.trace.TraceControl;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rtk State Serialization Test")
public class AmbiguityStateSerializationTest {

    @Test
    @DisplayName("1. Rtk object serialization round-trip")
    void testRtkSerializationRoundTrip() throws Exception {
        Rtk original = new Rtk();
        original.nx = 50;
        original.na = 10;
        original.nfix = 3;
        original.epoch = 100;
        original.holdambFlag = 1;
        original.nb_ar = 5;
        original.x[10] = 123.456;
        original.P[10 * Constants.NX_RTK + 10] = 0.001;
        original.xa[5] = 78.9;
        original.Pa[5 * Constants.NX_RTK + 5] = 0.002;
        original.ssat[0].fix[0] = 2;
        original.ssat[0].amb[0] = 0.123;
        original.ssat[0].lock[0] = 50;
        original.ambc.fixcnt = 10;
        original.ambAnchored[0] = true;
        original.ambAnchorCount[0] = 5;
        original.rb[0] = -2267749.0;
        original.rb[1] = 5009155.0;
        original.rb[2] = 3221290.0;
        original.rtkConfig.enableAmbAnchor = true;
        original.rtkConfig.ambAnchorMinFixCount = 50;

        byte[] data;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
            oos.flush();
            data = baos.toByteArray();
        }

        assertNotNull(data);
        assertTrue(data.length > 0);

        Rtk restored;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            restored = (Rtk) ois.readObject();
        }

        assertNotNull(restored);
        assertEquals(original.nx, restored.nx);
        assertEquals(original.na, restored.na);
        assertEquals(original.nfix, restored.nfix);
        assertEquals(original.epoch, restored.epoch);
        assertEquals(original.holdambFlag, restored.holdambFlag);
        assertEquals(original.nb_ar, restored.nb_ar);
        assertEquals(original.x[10], restored.x[10], 1e-10);
        assertEquals(original.P[10 * Constants.NX_RTK + 10], restored.P[10 * Constants.NX_RTK + 10], 1e-10);
        assertEquals(original.xa[5], restored.xa[5], 1e-10);
        assertEquals(original.Pa[5 * Constants.NX_RTK + 5], restored.Pa[5 * Constants.NX_RTK + 5], 1e-10);
        assertEquals(2, restored.ssat[0].fix[0]);
        assertEquals(0.123, restored.ssat[0].amb[0], 1e-10);
        assertEquals(50, restored.ssat[0].lock[0]);
        assertEquals(10, restored.ambc.fixcnt);
        assertTrue(restored.ambAnchored[0]);
        assertEquals(5, restored.ambAnchorCount[0]);
        assertEquals(-2267749.0, restored.rb[0], 1e-6);
        assertEquals(5009155.0, restored.rb[1], 1e-6);
        assertEquals(3221290.0, restored.rb[2], 1e-6);
        assertTrue(restored.rtkConfig.enableAmbAnchor);
        assertEquals(50, restored.rtkConfig.ambAnchorMinFixCount);
    }

    @Test
    @DisplayName("2. RtkProcessor getRtk + applyRtkState")
    void testRtkProcessorGetAndApply() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor processor = new RtkProcessor(opt);

        Rtk rtk = processor.getRtk();
        rtk.nx = 30;
        rtk.na = 9;
        rtk.nfix = 2;
        rtk.epoch = 50;
        rtk.holdambFlag = 1;
        rtk.nb_ar = 4;
        rtk.x[9] = 0.0567;
        rtk.P[9 * Constants.NX_RTK + 9] = 0.003;
        rtk.ssat[5].fix[0] = 2;
        rtk.ssat[5].amb[0] = -0.045;
        rtk.ssat[5].lock[0] = 30;
        rtk.ambc.fixcnt = 7;
        rtk.ambAnchored[5 * Constants.NFREQ + 0] = true;
        rtk.ambAnchorCount[5 * Constants.NFREQ + 0] = 3;
        rtk.rb[0] = -2267749.0;
        rtk.rb[1] = 5009155.0;
        rtk.rb[2] = 3221290.0;
        rtk.sol.prev_ratio1 = 2.5f;
        rtk.sol.prev_ratio2 = 3.0f;
        rtk.sol.thres = 3.0f;
        rtk.rtkConfig.enableAmbAnchor = true;

        Rtk saved = processor.getRtk();

        RtkProcessor processor2 = new RtkProcessor(opt);
        Rtk rtk2 = processor2.getRtk();
        assertEquals(0, rtk2.nfix);
        assertEquals(0, rtk2.epoch);

        boolean ok = processor2.applyRtkState(saved);
        assertTrue(ok);

        Rtk restored = processor2.getRtk();
        assertEquals(rtk.nx, restored.nx);
        assertEquals(rtk.na, restored.na);
        assertEquals(rtk.nfix, restored.nfix);
        assertEquals(rtk.epoch, restored.epoch);
        assertEquals(rtk.holdambFlag, restored.holdambFlag);
        assertEquals(rtk.nb_ar, restored.nb_ar);
        assertEquals(0.0567, restored.x[9], 1e-10);
        assertEquals(0.003, restored.P[9 * Constants.NX_RTK + 9], 1e-10);
        assertEquals(2, restored.ssat[5].fix[0]);
        assertEquals(-0.045, restored.ssat[5].amb[0], 1e-10);
        assertEquals(30, restored.ssat[5].lock[0]);
        assertEquals(7, restored.ambc.fixcnt);
        assertTrue(restored.ambAnchored[5 * Constants.NFREQ + 0]);
        assertEquals(3, restored.ambAnchorCount[5 * Constants.NFREQ + 0]);
        assertEquals(-2267749.0, restored.rb[0], 1e-6);
        assertEquals(2.5f, restored.sol.prev_ratio1, 1e-6);
        assertEquals(3.0f, restored.sol.prev_ratio2, 1e-6);
        assertEquals(3.0f, restored.sol.thres, 1e-6);
        assertTrue(restored.rtkConfig.enableAmbAnchor);
        assertEquals(opt.mode, restored.opt.mode);
        assertEquals(opt.nf, restored.opt.nf);
        assertEquals(opt.navsys, restored.opt.navsys);
    }

    @Test
    @DisplayName("3. PppProcessor getRtk + applyRtkState")
    void testPppProcessorGetAndApply() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        PppProcessor processor = new PppProcessor(opt);

        Rtk rtk = processor.getRtk();
        rtk.nx = 20;
        rtk.na = 9;
        rtk.nfix = 0;
        rtk.epoch = 300;
        rtk.x[9] = 0.0123;
        rtk.P[9 * Constants.NX_RTK + 9] = 0.0001;
        rtk.ssat[0].amb[0] = 0.056;

        Rtk saved = processor.getRtk();

        PppProcessor processor2 = new PppProcessor(opt);
        boolean ok = processor2.applyRtkState(saved);
        assertTrue(ok);

        Rtk restored = processor2.getRtk();
        assertEquals(20, restored.nx);
        assertEquals(9, restored.na);
        assertEquals(300, restored.epoch);
        assertEquals(0.0123, restored.x[9], 1e-10);
        assertEquals(0.0001, restored.P[9 * Constants.NX_RTK + 9], 1e-10);
        assertEquals(0.056, restored.ssat[0].amb[0], 1e-10);
    }

    @Test
    @DisplayName("4. applyRtkState with null returns false")
    void testApplyRtkStateNull() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor processor = new RtkProcessor(opt);
        assertFalse(processor.applyRtkState(null));
    }

    @Test
    @DisplayName("5. All data classes serialization")
    void testAllDataClassesSerializable() throws Exception {
        roundTrip(new GTime());
        roundTrip(new Sol());
        roundTrip(new Ssat());
        roundTrip(new Ambc());
        roundTrip(new Eph());
        roundTrip(new Geph());
        roundTrip(new Seph());
        roundTrip(new Nav());
        roundTrip(new PrcOpt());
        roundTrip(new SolOpt());
        roundTrip(new Sta());
        roundTrip(new Pcv());
        roundTrip(new Pclk());
        roundTrip(new PepH());
        roundTrip(new Erp());
        roundTrip(new Erpd());
        roundTrip(new Ssr());
        roundTrip(new Dgps());
        roundTrip(new Alm());
        roundTrip(new SnrMask());
        roundTrip(new SbsMsg());
        roundTrip(new SbsSat());
        roundTrip(new SbsIon());
        roundTrip(new SbsFCorr());
        roundTrip(new SbsLCorr());
        roundTrip(new SbsIgp());
        roundTrip(new SbsSatP());
        roundTrip(new Obs());
        roundTrip(new Obsd());
        roundTrip(new Rtk());
        roundTrip(new RtkConfig());
        roundTrip(new TraceControl());
        roundTrip(new Position(CoordType.ECEF, 0, 0, 0));
        roundTrip(new Velocity(CoordType.ECEF, 0, 0, 0));
        roundTrip(new Accuracy(CoordType.ENU, 0, 0, 0, 0, 0, 0));
        System.out.println("All data classes serialization verified.");
    }

    private <T extends Serializable> void roundTrip(T obj) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
            oos.flush();
            byte[] data = baos.toByteArray();

            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                 ObjectInputStream ois = new ObjectInputStream(bais)) {
                Object restored = ois.readObject();
                assertNotNull(restored);
                assertEquals(obj.getClass(), restored.getClass());
            }
        }
    }
}