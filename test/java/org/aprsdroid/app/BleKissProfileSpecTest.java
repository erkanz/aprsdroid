package org.aprsdroid.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

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

    @Test public void radtelUsesFfe1ForRxAndFf31ForTx() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb",
                BleKissProfileSpec.RADTEL_RT950.serviceUuid.toString());
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb",
                BleKissProfileSpec.RADTEL_RT950.rxUuid.toString());
        assertEquals("0000ff31-0000-1000-8000-00805f9b34fb",
                BleKissProfileSpec.RADTEL_RT950.txUuid.toString());
        assertFalse(BleKissProfileSpec.RADTEL_RT950.sameCharacteristic);
        // FF31 is the dedicated host-to-radio write characteristic. Prefer
        // acknowledged writes when supported so chunks stay serialized.
        assertFalse(BleKissProfileSpec.RADTEL_RT950.preferWriteWithoutResponse);
    }
}
