package org.aprsdroid.app

import _root_.android.util.Log
import _root_.java.io.{ByteArrayOutputStream, InputStream, OutputStream}

import _root_.net.ab0oo.aprs.parser._

class KissProto(service : AprsService, is : InputStream, os : OutputStream) extends TncProto(is, os) {
	val TAG = "APRSdroid.KissProto"

	object Kiss {
		// escape sequences
		val FEND  = 0xC0
		val FESC  = 0xDB
		val TFEND = 0xDC
		val TFESC = 0xDD

		// commands
		val CMD_DATA = 0x00
	}

	val initstring = java.net.URLDecoder.decode(service.prefs.getString("kiss.init", ""), "UTF-8")
	val initdelay = service.prefs.getStringInt("kiss.delay", 300)
	if (initstring != null && initstring != "") {
		for (line <- initstring.split("\n")) {
			service.postAddPost(StorageDatabase.Post.TYPE_TX,
				R.string.p_tnc_init, line)
			os.write(line.getBytes())
			os.write('\r')
			os.write('\n')
			Thread.sleep(initdelay)
		}
	}

	if (service.prefs.getCallsign().length() > 6) {
		throw new IllegalArgumentException(service.getString(R.string.e_toolong_callsign))
	}

	private def bytesToHex(data : Array[Byte]) : String =
		data.map(b => "%02X".format(b & 0xff)).mkString(" ")

	/**
	 * Some RT-950 OEM-generated frames omit AX.25 reserved bits 5/6 in the
	 * address SSID octets. They are semantically fixed bits, so setting them
	 * before a second parse attempt is safe and preserves C/H, SSID and the
	 * extension bit. This is only attempted after the normal parser rejects the
	 * original frame.
	 */
	private def normalizeAx25ReservedBits(frame : Array[Byte]) : Array[Byte] = {
		val fixed = frame.clone()
		var ssidOffset = 6
		var addresses = 0
		var done = false
		while (!done && ssidOffset < fixed.length && addresses < 10) {
			val original = fixed(ssidOffset) & 0xff
			fixed(ssidOffset) = (original | 0x60).toByte
			done = (original & 0x01) != 0
			ssidOffset += 7
			addresses += 1
		}
		fixed
	}

	private def parseAx25(frame : Array[Byte]) : String = {
		try {
			Parser.parseAX25(frame).toString().trim()
		} catch {
			case first : Exception =>
				val fixed = normalizeAx25ReservedBits(frame)
				if (!java.util.Arrays.equals(frame, fixed)) {
					try {
						val parsed = Parser.parseAX25(fixed).toString().trim()
						Log.w(TAG,
							"AX.25 accepted after reserved-bit normalization; raw=" +
							bytesToHex(frame))
						return parsed
					} catch {
						case _ : Exception =>
					}
				}

				// RT950 compatibility fallback: if javAX25 rejects the frame but the
				// binary frame is still a structurally valid AX.25 UI/no-layer3
				// packet, render it directly as TNC2. This intentionally does not
				// parse or repair the APRS information field. AprsService can still
				// log the packet as Received even when the APRS payload itself is
				// malformed or unsupported.
				try {
					val tnc2 = Ax25Tnc2Compat.decodeUiFrame(frame).trim()
					Log.w(TAG,
						"AX.25 accepted by strict UI-frame fallback; raw=" +
						bytesToHex(frame) + " tnc2=" + tnc2)
					return tnc2
				} catch {
					case fallback : Exception =>
						Log.w(TAG,
							"AX.25 parse rejected raw=" + bytesToHex(frame) +
							" fallback=" + fallback.toString(), first)
						throw first
				}
		}
	}

	def readPacket() : String = {
		import Kiss._
		val buf = scala.collection.mutable.ListBuffer[Byte]()
		do {
			var ch = is.read()
			if (ch >= 0)
				Log.d(TAG, "readPacket: %02X '%c'".format(ch, ch))
			ch match {
			case FEND =>
				if (buf.length > 0) {
					val frame = buf.toArray
					try {
						return parseAx25(frame)
					} catch {
						case _ : Exception => buf.clear()
					}
				}
			case FESC => is.read() match {
				case TFEND => buf.append(FEND.toByte)
				case TFESC => buf.append(FESC.toByte)
				case _ =>
				}
			case -1	=> throw new java.io.IOException("KissReader out of data")
			case 0 =>
				// Ignore KISS port 0/data command byte at the start of a frame.
				if (buf.length != 0)
					buf.append(ch.toByte)
				else
					Log.d(TAG, "readPacket: ignoring command byte")
			case 10 =>
				// heuristic for ASCII strings:
				//   * non-empty (including CRLF)
				//   * starts with ASCII character (KISS starts with >=0x82)
				//     (buf(0) > 0) does this check, as byte is [-128..127]
				//   * ends in CRLF
				if (buf.length > 1 && (buf(0) > 0) && buf(buf.length-1)==13)
					return new String(buf.toArray).trim()
			case _ =>
				buf.append(ch.toByte)
			}
		} while (true)
		""
	}

	private def escapeKissPayload(payload : Array[Byte]) : Array[Byte] = {
		import Kiss._
		val out = new ByteArrayOutputStream(payload.length + 8)
		payload.foreach { b =>
			(b & 0xff) match {
			case FEND =>
				out.write(FESC)
				out.write(TFEND)
			case FESC =>
				out.write(FESC)
				out.write(TFESC)
			case v => out.write(v)
			}
		}
		out.toByteArray()
	}

	def writePacket(p : APRSPacket) {
		Log.d(TAG, "writePacket: " + p)
		val ax25 = p.toAX25Frame()
		val escaped = escapeKissPayload(ax25)
		val combinedData =
			Array[Byte](Kiss.FEND.toByte, Kiss.CMD_DATA.toByte) ++
			escaped ++ Array[Byte](Kiss.FEND.toByte)
		Log.d(TAG, "*** KISS TX len=" + combinedData.length + " hex=" + bytesToHex(combinedData))
		os.write(combinedData)
		os.flush()
	}
}
