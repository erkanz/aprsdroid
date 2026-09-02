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
 * Standard BLE-KISS transport.
 *
 * BLE GATT packet boundaries are intentionally hidden from KissProto. RX
 * notifications are exposed as a continuous blocking byte stream; TX KISS
 * bytes are fragmented to the negotiated ATT payload size.
 */
class BluetoothLETnc(service : AprsService, prefs : PrefsWrapper)
		extends AprsBackend(prefs) {

	val TAG = "APRSdroid.BLEKISS"

	val SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
	val RX_UUID = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb")
	val TX_UUID = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb")
	val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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

		// Connection setup is asynchronous.
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

		@volatile var proto : TncProto = null
		@volatile var input : BLEInputStream = null
		@volatile var output : BLEOutputStream = null

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

		def writeCharacteristicCompat(data : Array[Byte]) : Boolean = {
			val cbGatt = gatt
			val characteristic = txCharacteristic

			if (cbGatt == null || characteristic == null || !connectionActive)
				return false

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				cbGatt.writeCharacteristic(
					characteristic,
					data,
					BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
			} else {
				characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
				characteristic.setValue(data)
				cbGatt.writeCharacteristic(characteristic)
			}
		}

		def handleRx(data : Array[Byte]) {
			if (data == null || data.length == 0)
				return

			val in = input
			if (in != null && connectionActive) {
				Log.d(TAG, "BLE RX " + data.length + " bytes")
				in.appendData(data)
			}
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

				Log.d(TAG, "GATT service discovery complete; enumerating services")
				try {
					val services = cbGatt.getServices()
					if (services != null) {
						val it = services.iterator()
						while (it.hasNext()) {
							val s = it.next()
							Log.d(TAG, "GATT SERVICE " + s.getUuid())
							val chars = s.getCharacteristics()
							if (chars != null) {
								val cit = chars.iterator()
								while (cit.hasNext()) {
									val ch = cit.next()
									Log.d(TAG,
										"GATT CHAR " + ch.getUuid() +
										" props=0x" + Integer.toHexString(ch.getProperties()) +
										" perms=0x" + Integer.toHexString(ch.getPermissions()))
									val descs = ch.getDescriptors()
									if (descs != null) {
										val dit = descs.iterator()
										while (dit.hasNext()) {
											val d = dit.next()
											Log.d(TAG, "GATT DESC " + d.getUuid())
										}
									}
								}
							}
						}
				} catch {
					case t : Throwable => Log.e(TAG, "Unable to enumerate GATT database", t)
				}

				val kissService = cbGatt.getService(SERVICE_UUID)
				if (kissService == null) {
					failConnection(service.getString(R.string.ble_error_service))
					return
				}

				rxCharacteristic = kissService.getCharacteristic(RX_UUID)
				txCharacteristic = kissService.getCharacteristic(TX_UUID)

				if (rxCharacteristic == null || txCharacteristic == null) {
					failConnection(service.getString(R.string.ble_error_characteristics))
					return
				}

				val rxProps = rxCharacteristic.getProperties()
				val txProps = txCharacteristic.getProperties()
				if ((rxProps & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 ||
				    (txProps & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
					failConnection(service.getString(R.string.ble_error_characteristics))
					return
				}

				if (!cbGatt.setCharacteristicNotification(rxCharacteristic, true)) {
					failConnection(service.getString(R.string.ble_error_subscribe))
					return
				}

				val cccd = rxCharacteristic.getDescriptor(CCCD_UUID)
				if (cccd == null ||
				    !writeDescriptorCompat(
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
					failConnection(service.getString(R.string.ble_error_subscribe))
					return
				}

				// Default ATT payload is 20 bytes. Become operational immediately,
				// then raise the TX chunk size if MTU negotiation succeeds.
				markTransportReady()
				try cbGatt.requestMtu(517) catch {
					case _ : Throwable =>
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

				if (gen == generation && characteristic.getUuid() == RX_UUID)
					handleRx(characteristic.getValue())
			}

			override def onCharacteristicChanged(
					cbGatt : BluetoothGatt,
					characteristic : BluetoothGattCharacteristic,
					value : Array[Byte]) {

				if (gen == generation && characteristic.getUuid() == RX_UUID)
					handleRx(value)
			}

			override def onCharacteristicWrite(
					cbGatt : BluetoothGatt,
					characteristic : BluetoothGattCharacteristic,
					status : Int) {

				if (gen != generation || characteristic.getUuid() != TX_UUID)
					return

				val out = output
				if (out != null)
					out.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
			}
		}

		def closeGatt() {
			generation += 1
			connectionActive = false
			transportReady = false

			val in = input
			if (in != null)
				in.closeStream()

			val out = output
			if (out != null)
				out.closeStream()

			val oldGatt = gatt
			gatt = null
			if (oldGatt != null) {
				try oldGatt.disconnect() catch { case _ : Throwable => }
				try oldGatt.close() catch { case _ : Throwable => }
			}

			rxCharacteristic = null
			txCharacteristic = null
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
			Log.d(TAG, "BLE KISS transport ready")
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

				val value = queue.take().intValue()
				value
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
		 * Serializes GATT writes and fragments KISS bytes according to the
		 * current ATT payload size (MTU - 3).
		 */
		class BLEOutputStream extends OutputStream {
			private val staged = new ByteArrayOutputStream()
			private val pending = new java.util.ArrayDeque[Array[Byte]]()

			@volatile private var attPayload = 20
			private var writeInFlight = false
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

				val chunk = pending.removeFirst()

				if (!writeCharacteristicCompat(chunk)) {
					pending.clear()
					failConnection("BLE characteristic write rejected")
					throw new IOException("BLE characteristic write rejected")
				}

				writeInFlight = true
				Log.d(TAG, "BLE TX " + chunk.length + " bytes")
			}

			def onWriteComplete(success : Boolean) : Unit = synchronized {
				writeInFlight = false

				if (!success) {
					pending.clear()
					failConnection("BLE characteristic write failed")
					return
				}

				try pump() catch {
					case e : IOException =>
						Log.e(TAG, "BLE TX pump failed", e)
				}
			}

			def closeStream() = synchronized {
				closed = true
				pending.clear()
				staged.reset()
				writeInFlight = false
			}

			override def close() {
				closeStream()
			}
		}
	}
}
