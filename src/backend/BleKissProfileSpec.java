package org.aprsdroid.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable BLE KISS transport profile descriptors. */
public final class BleKissProfileSpec {
    public static final String STANDARD_ID = "STANDARD_BLE_KISS";
    public static final String TWR_NUS_ID = "TWR_NUS_KISS";
    public static final String RADTEL_RT950_ID = "RADTEL_RT950_KISS";

    public static final UUID STANDARD_SERVICE =
            UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
    public static final UUID STANDARD_TX =
            UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb");
    public static final UUID STANDARD_RX =
            UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb");

    public static final UUID TWR_NUS_SERVICE =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TWR_NUS_TX =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TWR_NUS_RX =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    public static final UUID RADTEL_RT950_SERVICE =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID RADTEL_RT950_RX =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    public static final UUID RADTEL_RT950_TX =
            UUID.fromString("0000ff31-0000-1000-8000-00805f9b34fb");

    public static final BleKissProfileSpec STANDARD = new BleKissProfileSpec(
            STANDARD_ID, STANDARD_SERVICE, STANDARD_RX, STANDARD_TX, false, false);

    public static final BleKissProfileSpec TWR_NUS = new BleKissProfileSpec(
            TWR_NUS_ID, TWR_NUS_SERVICE, TWR_NUS_RX, TWR_NUS_TX, false, false);

    /**
     * RT-950 Pro proprietary BLE profile:
     *   FFE1 = notify/read path from radio to host (RX)
     *   FF31 = host-to-radio write path (TX)
     *
     * FFE1 happens to advertise WRITE as well on tested radios, but independent
     * RT950 protocol reverse engineering identifies FF31 as the actual TX
     * characteristic. Treating FFE1 as a shared RX/TX characteristic makes
     * writes succeed at the GATT layer while the radio ignores them for KISS TX.
     */
    public static final BleKissProfileSpec RADTEL_RT950 = new BleKissProfileSpec(
            RADTEL_RT950_ID,
            RADTEL_RT950_SERVICE,
            RADTEL_RT950_RX,
            RADTEL_RT950_TX,
            false,
            false);

    /** Detection order preserves the existing standard and TWR behavior. */
    public static final List<BleKissProfileSpec> DETECTION_ORDER =
            Collections.unmodifiableList(Arrays.asList(STANDARD, TWR_NUS, RADTEL_RT950));

    public final String id;
    public final UUID serviceUuid;
    public final UUID rxUuid;
    public final UUID txUuid;
    public final boolean sameCharacteristic;
    public final boolean preferWriteWithoutResponse;

    private BleKissProfileSpec(
            String id,
            UUID serviceUuid,
            UUID rxUuid,
            UUID txUuid,
            boolean sameCharacteristic,
            boolean preferWriteWithoutResponse) {
        this.id = id;
        this.serviceUuid = serviceUuid;
        this.rxUuid = rxUuid;
        this.txUuid = txUuid;
        this.sameCharacteristic = sameCharacteristic;
        this.preferWriteWithoutResponse = preferWriteWithoutResponse;
    }
}
