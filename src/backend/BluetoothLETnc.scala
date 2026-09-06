package org.aprsdroid.app

import _root_.android.bluetooth._
import _root_.android.os.{Build, Handler, Looper}
import _root_.android.util.Log
import _root_.java.io.{ByteArrayOutputStream, IOException, InputStream, OutputStream}
import _root_.java.util.UUID
import _root_.java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}
import _root_.java.util.concurrent.atomic.AtomicReference

import _root_.net.ab0oo.aprs.parser._

/**
 * BLE KISS byte-stream transport.
 *
 * Supported profiles are selected after GATT service discovery:
 *   - standard BLE-KISS UUID profile
 *   - TWR APRS Nordic UART Service profile
 *   - Radtel RT-950 Pro FFE0/FFE1 profile
 *
 * BLE notification/write boundaries never have KISS framing meaning. RX bytes
 * are exposed to the existing KissProto as one ordered blocking InputStream;
 * TX bytes emitted by KissProto are fragmented only for ATT transport.
 */
class BluetoothLETnc(service : AprsService, prefs : PrefsWrapper)
		extends AprsBackend(prefs) {

	val TAG = "APRSdroid.BLEKISS"
	val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
	val PROFILE_RADTEL_RT950 = BleKissProfileSpec.RADTEL_RT950_ID

	val tncmac = prefs.getString("ble.mac", null)
	var conn : BleGattThread = null

	def start() : Boolean = {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
			service.postAbort(service.getString(R.string.ble_error_unsupported))
			return false
		}

		try {
			val adapter = BluetoothAdapter.getDefaultAdapter()
			if (adapter == null) {
				service.postAbort(service.getString(R.string.bt_error_unsupported))
				return false
			}
			if (!adapter.isEnabled()) {
				service.postAbort(service.getString(R.string.bt_error_disabled))
				return false
			}
			if (tncmac == null || tncmac.trim().isEmpty()) {
				service.postAbort(service.getString(R.string.ble_error_no_tnc))
				return false
			}

			val device = adapter.getRemoteDevice(tncmac)
			conn = new BleGattThread(device)
			conn.start()
		} catch {
			case e : Exception =>
				Log.e(TAG, "Unable to start BLE KISS", e)
				service.postAbort(service.getString(R.string.ble_error_connect, tncmac))
		}

		// Connection setup is asynchronous. postPosterStarted() is emitted once
		// the transport has completed notification subscription.
		false
	}

	def update(packet : APRSPacket) : String = {
		if (conn == null) "BLE disconnected" else conn.update(packet)
	}

	def stop() {
		if (conn == null)
			return

		conn.shutdown()
		conn.interrupt()
		conn.join(250)
		conn = null
	}

	class BleGattThread(device : BluetoothDevice)
			extends Thread("APRSdroid BLE KISS connection") {

		val READY_TIMEOUT_MS = 15000L
		val CONNECT_CALL_TIMEOUT_MS = 5000L
		val INITIAL_GATT_ATTEMPTS = 3
		val GATT_RETRY_DELAY_MS = 750L
		val GATT_ERROR_133 = 133
		val NO_RESPONSE_PACE_MS = 20L
		val stateLock = new Object()
		val mainHandler = new Handler(Looper.getMainLooper())

		@volatile var running = true
		@volatile var transportReady = false
		@volatile var connectionActive = false
		@volatile var connectionError : String = null
		@volatile var generation = 0
		@volatile var lastGattStatus = BluetoothGatt.GATT_SUCCESS

		@volatile var gatt : BluetoothGatt = null
		@volatile var rxCharacteristic : BluetoothGattCharacteristic = null
		@volatile var txCharacteristic : BluetoothGattCharacteristic = null
		@volatile var activeServiceUuid : UUID = null
		@volatile var activeRxUuid : UUID = null
		@volatile var activeTxUuid : UUID = null
		@volatile var activeProfile = "none"
		@volatile var preferWriteNoResponse = false
		@volatile var rxNotifyAttached = false

		@volatile var proto : TncProto = null
		@volatile var input : BLEInputStream = null
		@volatile var output : BLEOutputStream = null
		@volatile var rxGuard : BleKissStreamGuard = null

		def log(message : String) {
			service.postAddPost(
				StorageDatabase.Post.TYPE_INFO,
				R.string.post_info,
				message)
		}

		def failConnection(message : String) {
			Log.d(TAG, "failConnection: " + message)
			connectionError = message
			connectionActive = false
			transportReady = false

			val in = input
			if (in != null)
				in.closeStream()

			val out = output
			if (out != null)
				out.closeStream()

			stateLock.synchronized {
				stateLock.notifyAll()
			}
		}

		def markTransportReady() {
			transportReady = true
			connectionActive = true
			connectionError = null
			stateLock.synchronized {
				stateLock.notifyAll()
			}
		}

		def waitForTransport() {
			val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS

			stateLock.synchronized {
				while (running && !transportReady && connectionError == null) {
					val remaining = deadline - System.currentTimeMillis()
					if (remaining <= 0)
						throw new IOException("BLE connection timed out")
					stateLock.wait(remaining)
				}
			}

			if (!running)
				throw new IOException("BLE connection stopped")
			if (!transportReady)
				throw new IOException(
					if (connectionError != null) connectionError
					else "BLE transport not ready")
		}

		def writeDescriptorCompat(
				cbGatt : BluetoothGatt,
				descriptor : BluetoothGattDescriptor,
				value : Array[Byte]) : Boolean = {

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				cbGatt.writeDescriptor(descriptor, value) == 0
			} else {
				descriptor.setValue(value)
				cbGatt.writeDescriptor(descriptor)
			}
		}

		def chooseWriteType() : Int = {
			val characteristic = txCharacteristic
			if (characteristic == null)
				return -1

			val props = characteristic.getProperties()
			val hasWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
			val hasWriteNoResponse =
				(props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

			if (preferWriteNoResponse && hasWriteNoResponse)
				BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
			else if (hasWrite)
				BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
			else if (hasWriteNoResponse)
				BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
			else
				-1
		}

		def writeCharacteristicCompat(data : Array[Byte], writeType : Int) : Boolean = {
			val cbGatt = gatt
			val characteristic = txCharacteristic

			if (cbGatt == null || characteristic == null || !connectionActive || writeType < 0)
				return false

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				cbGatt.writeCharacteristic(characteristic, data, writeType) == 0
			} else {
				characteristic.setWriteType(writeType)
				characteristic.setValue(data)
				cbGatt.writeCharacteristic(characteristic)
			}
		}

		def bytesToHex(data : Array[Byte]) : String = {
			val hex = "0123456789ABCDEF"
			val b = new StringBuilder(data.length * 3)
			var i = 0
			while (i < data.length) {
				if (i > 0)
					b.append(' ')
				val v = data(i) & 0xff
				b.append(hex.charAt((v >>> 4) & 0x0f))
				b.append(hex.charAt(v & 0x0f))
				i += 1
			}
			b.toString()
		}

		def shortUuid(uuid : UUID) : String = {
			if (uuid == null)
				"-"
			else {
				val s = uuid.toString().toUpperCase(java.util.Locale.US)
				if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805F9B34FB"))
					s.substring(4, 8)
				else
					s
			}
		}

		def isRadtelProfile : Boolean = activeProfile == PROFILE_RADTEL_RT950

		def handleRx(data : Array[Byte]) {
			if (data == null || data.length == 0)
				return

			val in = input
			if (in != null && connectionActive) {
				Log.d(TAG,
					"*** BLE RX profile=" + activeProfile +
					" uuid=" + shortUuid(activeRxUuid) +
					" len=" + data.length +
					" hex=" + bytesToHex(data))

				val guard = rxGuard
				val bytes = if (guard != null) guard.filter(data) else data
				if (bytes != null && bytes.length > 0)
					in.appendData(bytes)
			}
		}

		def makeStreamGuard() : BleKissStreamGuard = {
			new BleKissStreamGuard(new BleKissStreamGuard.Listener {
				override def onFrame(length : Int, port : Int, command : Int) {
					val commandName =
						if (command == 0) "DATA"
						else "0x%X".format(command)
					Log.d(TAG,
						"*** KISS FRAME RX profile=" + activeProfile +
						" length=" + length +
						" port=" + port +
						" command=" + commandName)
				}

				override def onReset(reason : String) {
					Log.w(TAG, "*** KISS RX RESET reason=" + reason)
				}
			})
		}

		def selectProfile(cbGatt : BluetoothGatt) : Boolean = {
			val profiles = BleKissProfileSpec.DETECTION_ORDER.iterator()
			var selectedSpec : BleKissProfileSpec = null
			var selectedService : BluetoothGattService = null
			var selectedRx : BluetoothGattCharacteristic = null
			var selectedTx : BluetoothGattCharacteristic = null

			while (profiles.hasNext() && selectedSpec == null) {
				val spec = profiles.next()
				val candidateService = cbGatt.getService(spec.serviceUuid)
				if (candidateService != null) {
					val candidateRx = candidateService.getCharacteristic(spec.rxUuid)
					val candidateTx = candidateService.getCharacteristic(spec.txUuid)
					if (candidateRx != null && candidateTx != null) {
						val rxProps = candidateRx.getProperties()
						val txProps = candidateTx.getProperties()
						val rxNotifies =
							(rxProps & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
						val txWritable =
							(txProps & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
							(txProps & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

						if (rxNotifies && txWritable) {
							selectedSpec = spec
							selectedService = candidateService
							selectedRx = candidateRx
							selectedTx = candidateTx
						}
					}
				}
			}

			if (selectedSpec == null || selectedService == null) {
				failConnection(service.getString(R.string.ble_error_service))
				return false
			}

			activeProfile = selectedSpec.id
			activeServiceUuid = selectedSpec.serviceUuid
			activeRxUuid = selectedSpec.rxUuid
			activeTxUuid = selectedSpec.txUuid
			preferWriteNoResponse = selectedSpec.preferWriteWithoutResponse
			rxCharacteristic = selectedRx
			txCharacteristic = selectedTx

			Log.d(TAG,
				"BLE KISS profile=" + activeProfile +
				" service=" + activeServiceUuid +
				" tx=" + activeTxUuid +
				" rx=" + activeRxUuid)

			if (isRadtelProfile) {
				Log.d(TAG,
					"*** PROFILE=RADTEL_RT950_KISS service=FFE0 rx=FFE1 tx=FFE1 " +
					"sameCharacteristic=" + (rxCharacteristic eq txCharacteristic))
			}

			true
		}

		def makeCallback(gen : Int) = new BluetoothGattCallback {
			override def onConnectionStateChange(
					cbGatt : BluetoothGatt,
					status : Int,
					newState : Int) {

				if (gen != generation)
					return

				lastGattStatus = status
				Log.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState)
				if (status != BluetoothGatt.GATT_SUCCESS) {
					failConnection("GATT status " + status)
					return
				}

				newState match {
					case BluetoothProfile.STATE_CONNECTED =>
						Log.d(TAG, "BLE connected; discovering services")
						if (!cbGatt.discoverServices())
							failConnection("Could not start BLE service discovery")

					case BluetoothProfile.STATE_DISCONNECTED =>
						failConnection("BLE disconnected")

					case _ =>
				}
			}

			override def onServicesDiscovered(cbGatt : BluetoothGatt, status : Int) {
				if (gen != generation)
					return

				if (status != BluetoothGatt.GATT_SUCCESS) {
					failConnection("BLE service discovery failed: " + status)
					return
				}

				if (!selectProfile(cbGatt))
					return

				if (!cbGatt.setCharacteristicNotification(rxCharacteristic, true)) {
					failConnection(service.getString(R.string.ble_error_subscribe))
					return
				}
				rxNotifyAttached = true

				if (isRadtelProfile)
					Log.d(TAG, "*** FFE1 VALUECHANGED HANDLER ATTACHED")

				val cccd = rxCharacteristic.getDescriptor(CCCD_UUID)
				if (cccd == null) {
					failConnection(service.getString(R.string.ble_error_subscribe))
					return
				}

				if (isRadtelProfile)
					Log.d(TAG, "*** FFE1 CCCD WRITE START")

				if (!writeDescriptorCompat(
						cbGatt,
						cccd,
						BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
					failConnection(service.getString(R.string.ble_error_subscribe))
				}
			}

			override def onDescriptorWrite(
					cbGatt : BluetoothGatt,
					descriptor : BluetoothGattDescriptor,
					status : Int) {

				if (gen != generation || descriptor.getUuid() != CCCD_UUID)
					return

				if (status != BluetoothGatt.GATT_SUCCESS) {
					if (isRadtelProfile)
						Log.d(TAG, "*** FFE1 CCCD WRITE RESULT=Failure status=" + status)
					failConnection(service.getString(R.string.ble_error_subscribe))
					return
				}

				if (isRadtelProfile) {
					Log.d(TAG, "*** FFE1 CCCD WRITE RESULT=Success")
					Log.d(TAG, "*** FFE1 NOTIFY ACTIVE")
				}

				// A BLE connection is not transport-ready until notification CCCD
				// subscription has completed successfully.
				markTransportReady()

				if (isRadtelProfile) {
					// RT950/HM-10 KISS is hardware-qualified with the default 23-byte
					// ATT MTU and 20-byte chunks. Do not impose a large MTU request.
					Log.d(TAG, "*** RADTEL KISS READY")
				} else {
					// Existing standard/TWR behavior: become operational first, then
					// increase TX chunk size if Android and the peripheral accept it.
					try cbGatt.requestMtu(517) catch {
						case _ : Throwable =>
					}
				}
			}

			override def onMtuChanged(cbGatt : BluetoothGatt, mtu : Int, status : Int) {
				if (gen != generation)
					return

				if (status == BluetoothGatt.GATT_SUCCESS) {
					val payload = math.max(20, mtu - 3)
					val out = output
					if (out != null)
						out.setAttPayload(payload)
					Log.d(TAG, "BLE MTU=" + mtu + " ATT payload=" + payload)
				}
			}

			override def onCharacteristicChanged(
					cbGatt : BluetoothGatt,
					characteristic : BluetoothGattCharacteristic) {

				if (gen == generation && activeRxUuid != null &&
				    characteristic.getUuid() == activeRxUuid)
					handleRx(characteristic.getValue())
			}

			override def onCharacteristicChanged(
					cbGatt : BluetoothGatt,
					characteristic : BluetoothGattCharacteristic,
					value : Array[Byte]) {

				if (gen == generation && activeRxUuid != null &&
				    characteristic.getUuid() == activeRxUuid)
					handleRx(value)
			}

			override def onCharacteristicWrite(
					cbGatt : BluetoothGatt,
					characteristic : BluetoothGattCharacteristic,
					status : Int) {

				if (gen != generation || activeTxUuid == null ||
				    characteristic.getUuid() != activeTxUuid)
					return

				val out = output
				if (out != null)
					out.onGattWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
			}
		}

		def closeGatt() {
			generation += 1
			connectionActive = false
			transportReady = false

			val oldGatt = gatt
			val oldRx = rxCharacteristic
			val hadNotify = rxNotifyAttached

			// Disable the local notification route exactly once for this GATT
			// generation. Closing the GATT releases the remote CCCD/subscription.
			if (oldGatt != null && oldRx != null && hadNotify) {
				try oldGatt.setCharacteristicNotification(oldRx, false) catch {
					case _ : Throwable =>
				}
			}
			rxNotifyAttached = false

			val guard = rxGuard
			if (guard != null)
				guard.reset()
			rxGuard = null

			val in = input
			if (in != null)
				in.closeStream()

			val out = output
			if (out != null)
				out.closeStream()

			gatt = null
			if (oldGatt != null) {
				try oldGatt.disconnect() catch { case _ : Throwable => }
				try oldGatt.close() catch { case _ : Throwable => }
			}

			rxCharacteristic = null
			txCharacteristic = null
			activeServiceUuid = null
			activeRxUuid = null
			activeTxUuid = null
			activeProfile = "none"
			preferWriteNoResponse = false
		}

		def connectGattOnMainThread(callback : BluetoothGattCallback) : BluetoothGatt = {
			val result = new AtomicReference[BluetoothGatt]()
			val failure = new AtomicReference[Throwable]()
			val latch = new CountDownLatch(1)

			mainHandler.post(new Runnable {
				override def run() {
					try {
						val context = service.getApplicationContext()
						val newGatt =
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
								device.connectGatt(
									context,
									false,
									callback,
									BluetoothDevice.TRANSPORT_LE,
									BluetoothDevice.PHY_LE_1M_MASK,
									mainHandler)
							else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
								device.connectGatt(
									context,
									false,
									callback,
									BluetoothDevice.TRANSPORT_LE)
							else
								device.connectGatt(context, false, callback)

						result.set(newGatt)
					} catch {
						case t : Throwable => failure.set(t)
					} finally {
						latch.countDown()
					}
				}
			})

			if (!latch.await(CONNECT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS))
				throw new IOException("connectGatt call timed out")

			val thrown = failure.get()
			if (thrown != null)
				throw new IOException("connectGatt failed", thrown)

			result.get()
		}

		def initConnectionOnce(attempt : Int) {
			closeGatt()

			connectionError = null
			transportReady = false
			connectionActive = false
			lastGattStatus = BluetoothGatt.GATT_SUCCESS
			input = new BLEInputStream()
			output = new BLEOutputStream()
			rxGuard = makeStreamGuard()
			proto = null

			generation += 1
			val gen = generation
			val callback = makeCallback(gen)

			Log.d(TAG,
				"Connecting BLE KISS to " + tncmac +
				" attempt=" + attempt + "/" + INITIAL_GATT_ATTEMPTS +
				" bondState=" + device.getBondState() +
				" deviceType=" + device.getType() +
				" sdk=" + Build.VERSION.SDK_INT)

			gatt = connectGattOnMainThread(callback)

			if (gatt == null)
				throw new IOException("connectGatt returned null")

			waitForTransport()
			proto = AprsBackend.instanciateProto(service, input, output)
			Log.d(TAG, "BLE KISS transport ready profile=" + activeProfile)
		}

		def initConnection() {
			var attempt = 1
			var lastError : Exception = null

			while (running && attempt <= INITIAL_GATT_ATTEMPTS) {
				try {
					initConnectionOnce(attempt)
					return
				} catch {
					case e : Exception =>
						lastError = e

						if (lastGattStatus == GATT_ERROR_133 &&
						    attempt < INITIAL_GATT_ATTEMPTS &&
						    running) {
							Log.w(TAG,
								"GATT 133 on attempt " + attempt +
								"; closing GATT and retrying")
							closeGatt()
							try Thread.sleep(GATT_RETRY_DELAY_MS * attempt) catch {
								case _ : InterruptedException =>
							}
							attempt += 1
						} else {
							throw e
						}
				}
			}

			if (lastError != null)
				throw lastError
			throw new IOException("BLE connection stopped")
		}

		override def run() {
			running = true
			var needReconnect = false

			try {
				initConnection()
				service.postPosterStarted()
			} catch {
				case e : IllegalArgumentException =>
					service.postAbort(e.getMessage())
					running = false

				case e : Exception =>
					Log.e(TAG, "Initial BLE connection failed", e)
					service.postAbort(
						service.getString(R.string.ble_error_connect, tncmac))
					running = false
			}

			while (running) {
				try {
					if (needReconnect) {
						log(service.getString(R.string.ble_reconnecting))
						try Thread.sleep(3000) catch {
							case _ : InterruptedException =>
						}

						if (!running)
							throw new IOException("BLE connection stopped")

						initConnection()
						needReconnect = false
						service.postLinkOn(R.string.p_link_ble)
					}

					while (running && connectionActive) {
						val line = proto.readPacket()
						Log.d(TAG, "recv: " + line)
						Log.d(TAG, "*** APRS PACKET ACCEPTED profile=" + activeProfile)
						service.postSubmit(line)
					}

					if (running)
						throw new IOException(
							if (connectionError != null) connectionError
							else "BLE disconnected")
				} catch {
					case e : Exception =>
						if (running && !needReconnect) {
							service.postLinkOff(R.string.p_link_ble)
							service.postAddPost(
								StorageDatabase.Post.TYPE_INFO,
								R.string.post_error,
								e.toString())
						}

						if (running)
							needReconnect = true

						closeGatt()
				}
			}

			closeGatt()
		}

		def update(packet : APRSPacket) : String = {
			val p = proto
			if (p == null || !connectionActive)
				return "BLE disconnected"

			try {
				p.writePacket(packet)
				"BLE OK"
			} catch {
				case e : Exception =>
					Log.e(TAG, "BLE TX failed", e)
					failConnection(e.toString())
					"BLE disconnected"
			}
		}

		def shutdown() {
			running = false

			val p = proto
			if (p != null)
				try p.stop() catch { case _ : Throwable => }

			closeGatt()

			stateLock.synchronized {
				stateLock.notifyAll()
			}
		}

		/**
		 * Blocking byte stream. BLE notification lengths have no framing
		 * meaning; bytes are delivered to KissProto exactly in arrival order.
		 */
		class BLEInputStream extends InputStream {
			private val queue = new LinkedBlockingQueue[Integer]()
			@volatile private var closed = false

			def appendData(data : Array[Byte]) {
				if (closed || data == null)
					return

				var i = 0
				while (i < data.length) {
					queue.offer(Integer.valueOf(data(i) & 0xff))
					i += 1
				}
			}

			override def read() : Int = {
				if (closed && queue.isEmpty)
					return -1

				queue.take().intValue()
			}

			def closeStream() {
				if (!closed) {
					closed = true
					queue.offer(Integer.valueOf(-1))
				}
			}

			override def close() {
				closeStream()
			}
		}

		/**
		 * Serializes GATT writes and fragments KissProto output according to
		 * the current ATT payload size. RT950 uses write-without-response with
		 * a paced one-operation queue; other profiles keep response-first
		 * behavior and are advanced by onCharacteristicWrite.
		 */
		class BLEOutputStream extends OutputStream {
			private val staged = new ByteArrayOutputStream()
			private val pending = new java.util.ArrayDeque[Array[Byte]]()

			@volatile private var attPayload = 20
			private var writeInFlight = false
			private var currentWriteWithResponse = false
			private var writeSequence = 0L
			private var closed = false

			def setAttPayload(size : Int) = synchronized {
				attPayload = math.max(20, size)
			}

			override def write(b : Int) = synchronized {
				if (closed)
					throw new IOException("BLE output closed")
				staged.write(b)
			}

			override def write(b : Array[Byte], off : Int, len : Int) = synchronized {
				if (closed)
					throw new IOException("BLE output closed")
				staged.write(b, off, len)
			}

			override def flush() = synchronized {
				if (closed)
					throw new IOException("BLE output closed")

				val data = staged.toByteArray()
				staged.reset()

				var pos = 0
				while (pos < data.length) {
					val count = math.min(attPayload, data.length - pos)
					pending.addLast(
						java.util.Arrays.copyOfRange(data, pos, pos + count))
					pos += count
				}

				pump()
			}

			private def pump() {
				if (writeInFlight || pending.isEmpty || closed)
					return

				val writeType = chooseWriteType()
				if (writeType < 0) {
					pending.clear()
					failConnection("BLE characteristic is not writable")
					throw new IOException("BLE characteristic is not writable")
				}

				val chunk = pending.removeFirst()
				writeSequence += 1
				val sequence = writeSequence
				currentWriteWithResponse =
					writeType != BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
				writeInFlight = true

				if (!writeCharacteristicCompat(chunk, writeType)) {
					writeInFlight = false
					currentWriteWithResponse = false
					pending.clear()
					failConnection("BLE characteristic write rejected")
					throw new IOException("BLE characteristic write rejected")
				}

				Log.d(TAG,
					"BLE TX profile=" + activeProfile +
					" len=" + chunk.length +
					" type=" +
					(if (currentWriteWithResponse) "WITH_RESPONSE" else "NO_RESPONSE"))

				if (!currentWriteWithResponse) {
					// WRITE_NO_RESPONSE has no ATT acknowledgement. Pace the queue and
					// deliberately do not depend on a vendor-specific callback timing.
					mainHandler.postDelayed(new Runnable {
						override def run() {
							onNoResponsePaced(sequence)
						}
					}, NO_RESPONSE_PACE_MS)
				}
			}

			def onGattWriteComplete(success : Boolean) : Unit = synchronized {
				// Some Android stacks still issue onCharacteristicWrite for a
				// no-response command. That callback is ignored; the paced path is
				// the single owner of completion for that operation.
				if (!writeInFlight || !currentWriteWithResponse)
					return

				writeInFlight = false
				currentWriteWithResponse = false

				if (!success) {
					pending.clear()
					failConnection("BLE characteristic write failed")
					return
				}

				try pump() catch {
					case e : IOException => Log.e(TAG, "BLE TX pump failed", e)
				}
			}

			private def onNoResponsePaced(sequence : Long) : Unit = synchronized {
				if (closed || !writeInFlight || currentWriteWithResponse ||
				    sequence != writeSequence)
					return

				writeInFlight = false
				try pump() catch {
					case e : IOException => Log.e(TAG, "BLE TX pump failed", e)
				}
			}

			def closeStream() = synchronized {
				closed = true
				pending.clear()
				staged.reset()
				writeInFlight = false
				currentWriteWithResponse = false
				writeSequence += 1
			}

			override def close() {
				closeStream()
			}
		}
	}
}
