#!/usr/bin/env python3
"""Build-time patch for the paired RT950 RTX1 diagnostic APK.

Keeps the normal BLE/KISS receive path intact. Only RT950 transmit is changed:
APRSPacket -> raw AX.25 -> RTX1 envelope -> FFE1 writes.

The matching v0.8b diagnostic firmware returns raw ASCII "RTX" over USART1
only after RTX1 magic matched, CRC/AX.25 decoding succeeded, the decoded
packet was copied into the OEM TX buffer, and TX state was armed. This build
recognizes that marker across arbitrary BLE notification boundaries and posts
it visibly instead of feeding those three diagnostic bytes into KISS.
"""
from pathlib import Path

p = Path("src/backend/BluetoothLETnc.scala")
s = p.read_text()

old_pace = "\t\tval NO_RESPONSE_PACE_MS = 20L"
new_pace = "\t\tval NO_RESPONSE_PACE_MS = 5L"
if old_pace not in s:
    raise SystemExit("ERROR: expected BLE no-response pace line not found")
s = s.replace(old_pace, new_pace, 1)

old_state = "\t\t@volatile var rxNotifyAttached = false\n"
new_state = old_state + "\t\t@volatile var rtxAckState = 0\n"
if old_state not in s:
    raise SystemExit("ERROR: expected RX notify state line not found")
s = s.replace(old_state, new_state, 1)

old_handle = '''\t\tdef handleRx(data : Array[Byte]) {
\t\t\tif (data == null || data.length == 0)
\t\t\t\treturn

\t\t\tval in = input
\t\t\tif (in != null && connectionActive) {
\t\t\t\tLog.d(TAG,
\t\t\t\t\t"*** BLE RX profile=" + activeProfile +
\t\t\t\t\t" uuid=" + shortUuid(activeRxUuid) +
\t\t\t\t\t" len=" + data.length +
\t\t\t\t\t" hex=" + bytesToHex(data))

\t\t\t\tval guard = rxGuard
\t\t\t\tval bytes = if (guard != null) guard.filter(data) else data
\t\t\t\tif (bytes != null && bytes.length > 0)
\t\t\t\t\tin.appendData(bytes)
\t\t\t}
\t\t}
'''

new_handle = '''\t\tdef handleRx(data : Array[Byte]) {
\t\t\tif (data == null || data.length == 0)
\t\t\t\treturn

\t\t\tval in = input
\t\t\tif (in != null && connectionActive) {
\t\t\t\tLog.d(TAG,
\t\t\t\t\t"*** BLE RX profile=" + activeProfile +
\t\t\t\t\t" uuid=" + shortUuid(activeRxUuid) +
\t\t\t\t\t" len=" + data.length +
\t\t\t\t\t" hex=" + bytesToHex(data))

\t\t\t\tif (isRadtelProfile) {
\t\t\t\t\tval kept = new ByteArrayOutputStream()
\t\t\t\t\tvar i = 0
\t\t\t\t\twhile (i < data.length) {
\t\t\t\t\t\tval v = data(i) & 0xff
\t\t\t\t\t\tval expected = if (rtxAckState == 0) 0x52 else if (rtxAckState == 1) 0x54 else 0x58
\t\t\t\t\t\tif (v == expected) {
\t\t\t\t\t\t\trtxAckState += 1
\t\t\t\t\t\t\tif (rtxAckState == 3) {
\t\t\t\t\t\t\t\trtxAckState = 0
\t\t\t\t\t\t\t\tLog.d(TAG, "*** RT950 RTX1 ACK=RTX MCU_DECODE_STATE_PASS")
\t\t\t\t\t\t\t\tlog("RT950 RTX1 ACK: MCU decode + TX state PASS")
\t\t\t\t\t\t\t}
\t\t\t\t\t\t} else {
\t\t\t\t\t\t\tif (rtxAckState > 0) {
\t\t\t\t\t\t\t\tkept.write(0x52)
\t\t\t\t\t\t\t\tif (rtxAckState > 1) kept.write(0x54)
\t\t\t\t\t\t\t\trtxAckState = 0
\t\t\t\t\t\t\t}
\t\t\t\t\t\t\tkept.write(v)
\t\t\t\t\t\t}
\t\t\t\t\t\ti += 1
\t\t\t\t\t}
\t\t\t\t\tval filtered = kept.toByteArray()
\t\t\t\t\tif (filtered.length == 0)
\t\t\t\t\t\treturn
\t\t\t\t\tval guard = rxGuard
\t\t\t\t\tval bytes = if (guard != null) guard.filter(filtered) else filtered
\t\t\t\t\tif (bytes != null && bytes.length > 0)
\t\t\t\t\t\tin.appendData(bytes)
\t\t\t\t\treturn
\t\t\t\t}

\t\t\t\tval guard = rxGuard
\t\t\t\tval bytes = if (guard != null) guard.filter(data) else data
\t\t\t\tif (bytes != null && bytes.length > 0)
\t\t\t\t\tin.appendData(bytes)
\t\t\t}
\t\t}
'''

if old_handle not in s:
    raise SystemExit("ERROR: expected BLE handleRx block not found")
s = s.replace(old_handle, new_handle, 1)

old_update = '''\t\tdef update(packet : APRSPacket) : String = {
\t\t\tval p = proto
\t\t\tif (p == null || !connectionActive)
\t\t\t\treturn "BLE disconnected"

\t\t\ttry {
\t\t\t\tp.writePacket(packet)
\t\t\t\t"BLE OK"
\t\t\t} catch {
\t\t\t\tcase e : Exception =>
\t\t\t\t\tLog.e(TAG, "BLE TX failed", e)
\t\t\t\t\tfailConnection(e.toString())
\t\t\t\t\t"BLE disconnected"
\t\t\t}
\t\t}
'''

new_update = '''\t\tdef update(packet : APRSPacket) : String = {
\t\t\tif (!connectionActive)
\t\t\t\treturn "BLE disconnected"

\t\t\ttry {
\t\t\t\tif (isRadtelProfile) {
\t\t\t\t\tval out = output
\t\t\t\t\tif (out == null)
\t\t\t\t\t\treturn "BLE disconnected"
\t\t\t\t\tval ax25 = packet.toAX25Frame()
\t\t\t\t\tval wire = RadtelRtx1Codec.encode(ax25)
\t\t\t\t\tLog.d(TAG,
\t\t\t\t\t\t"*** RT950 RTX1 TX uuid=" + shortUuid(activeTxUuid) +
\t\t\t\t\t\t" ax25Len=" + ax25.length +
\t\t\t\t\t\t" wireLen=" + wire.length +
\t\t\t\t\t\t" hex=" + bytesToHex(wire))
\t\t\t\t\tout.write(wire)
\t\t\t\t\tout.flush()
\t\t\t\t} else {
\t\t\t\t\tval p = proto
\t\t\t\t\tif (p == null)
\t\t\t\t\t\treturn "BLE disconnected"
\t\t\t\t\tp.writePacket(packet)
\t\t\t\t}
\t\t\t\t"BLE OK"
\t\t\t} catch {
\t\t\t\tcase e : Exception =>
\t\t\t\t\tLog.e(TAG, "BLE TX failed", e)
\t\t\t\t\tfailConnection(e.toString())
\t\t\t\t\t"BLE disconnected"
\t\t\t}
\t\t}
'''

if old_update not in s:
    raise SystemExit("ERROR: expected BLE update(packet) block not found")
s = s.replace(old_update, new_update, 1)

p.write_text(s)
print("RT950 RTX1 diagnostic build patch applied")
print("- RT950 TX: raw AX.25 -> RTX1 -> shared FFE1")
print("- no-response chunk pace: 5 ms")
print("- recognizes firmware RTX ACK across BLE notification boundaries")
print("- RX KISS path unchanged")
