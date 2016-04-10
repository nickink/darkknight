package org.empyrn.darkknight.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import org.empyrn.darkknight.BuildConfig;

/**
 * Created by nick on 2/24/16.
 */
class BluetoothMessageHandler extends Handler {
	static final int MESSAGE_STATE_CHANGE = 1;
	static final int MESSAGE_READ = 2;
	static final int MESSAGE_WRITE = 3;
	static final int MESSAGE_BLUETOOTH_DEVICE_CONNECTED = 4;
	static final int MESSAGE_CONNECTION_FAILED = 5;

	// key names received from the BluetoothGameEventListener Handler
	static final String BLUETOOTH_DEVICE = "BluetoothDevice";

	private Callback mCallback;


	interface Callback {
		void onBluetoothStateChange(BluetoothDevice fromDevice, int code);
		void onBluetoothMessageReceived(BluetoothDevice fromDevice, String message);
		void onBluetoothDeviceConnected(BluetoothDevice device);
		void onBluetoothConnectionFailed(BluetoothDevice device);
	}

	BluetoothMessageHandler() {
		super(Looper.getMainLooper());
	}

	public void setCallback(Callback callback) {
		mCallback = callback;
	}

	@Override
	public void handleMessage(Message msg) {
		switch (msg.what) {
			case MESSAGE_STATE_CHANGE:
				if (BuildConfig.DEBUG) {
					Log.i(getClass().getSimpleName(), "MESSAGE_STATE_CHANGE: " + msg.arg1);
				}

				mCallback.onBluetoothStateChange(null, msg.arg1);
				break;
			case MESSAGE_WRITE:
				byte[] writeBuf = (byte[]) msg.obj;
				// construct a string from the buffer
				String writeMessage = new String(writeBuf);

				if (BuildConfig.DEBUG) {
					Log.i(getClass().getSimpleName(), "BLUETOOTH_out: " + writeMessage);
				}

				break;
			case MESSAGE_READ:
				byte[] readBuf = (byte[]) msg.obj;
				// construct a string from the valid bytes in the buffer
				String readMessage = new String(readBuf, 0, msg.arg1);

				if (BuildConfig.DEBUG) {
					Log.i(getClass().getSimpleName(), "BLUETOOTH_in: " + readMessage);
				}

				mCallback.onBluetoothMessageReceived(msg.getData().<BluetoothDevice>getParcelable(BLUETOOTH_DEVICE), readMessage);
				break;
			case MESSAGE_CONNECTION_FAILED:
				mCallback.onBluetoothConnectionFailed(msg.getData().<BluetoothDevice>getParcelable(BLUETOOTH_DEVICE));
				break;
			case MESSAGE_BLUETOOTH_DEVICE_CONNECTED:
				// save the info about the connected device
				mCallback.onBluetoothDeviceConnected(msg.getData().<BluetoothDevice>getParcelable(BLUETOOTH_DEVICE));
				break;
		}
	}
}
