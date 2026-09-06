package org.aprsdroid.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BleKissProfileSpecTest {
    @Test public void detectionOrderPreservesExistingProfilesBeforeRadtel() {
        assertEquals(3, BleKissProfileSpec.DETECTION_ORDER.size());
        assertSame(BleKissProfileSpec.STANDARD, BleKissProfileSpec.DETECTION_ORDER.get(0));
        assertSame(BleKissProfileSpec.TWR_NUS, BleKissProfileSpec.DETECTION_ORDER.get(1));
        assertSame(BleKissProfileSpec.RADTEL_RT950, BleKissProfileSpec.DETECTION_ORDER.get(2));
    }

    @Test public void standardAndTwrKeepSeparateRxTxCharacteristics() {
        assertFalse(BleKissProfileSpec.STANDARD.sameCharacteristic);
        assertFalse(BleKissProfileSpec.TWR_NUS.sameCharacteristic);
        assertFalse(BleKissProfileSpec.STANDARD.rxUuid.equals(BleKissProfileSpec.STANDARD.txUuid));
        assertFalse(BleKissProfileSpec.TWR_NUS.rxUuid.equals(BleKissProfileSpec.TWR_NUS.txUuid));
    }

    @Test public void radtelUsesFfe0AndSameFfe1ForRxAndTx() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb",
                BleKissProfileSpec.RADTEL_RT950.serviceUuid.toString());
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb",
                BleKissProfileSpec.RADTEL_RT950.rxUuid.toString());
        assertEquals(BleKissProfileSpec.RADTEL_RT950.rxUuid,
                BleKissProfileSpec.RADTEL_RT950.txUuid);
        assertTrue(BleKissProfileSpec.RADTEL_RT950.sameCharacteristic);
        assertTrue(BleKissProfileSpec.RADTEL_RT950.preferWriteWithoutResponse);
    }
}
