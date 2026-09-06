#!/usr/bin/env python3
"""Build-time patch for the paired RT950 RTX1 debug APK.

Keeps the normal BLE/KISS receive path intact. Only RT950 transmit is changed:
APRSPacket -> raw AX.25 -> RTX1 envelope -> FFE1 writes.
"""
from pathlib import Path

p = Path("src/backend/BluetoothLETnc.scala")
s = p.read_text()

old_pace = "\t\tval NO_RESPONSE_PACE_MS = 20L"
new_pace = "\t\tval NO_RESPONSE_PACE_MS = 5L"
if old_pace not in s:
    raise SystemExit("ERROR: expected BLE no-response pace line not found")
s = s.replace(old_pace, new_pace, 1)

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
print("RT950 RTX1 debug build patch applied")
print("- RT950 TX: raw AX.25 -> RTX1 -> shared FFE1")
print("- no-response chunk pace: 5 ms")
print("- RX path unchanged")
