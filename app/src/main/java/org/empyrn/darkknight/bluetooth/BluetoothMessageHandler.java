package org.empyrn.darkknight.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.empyrn.darkknight.BuildConfig;

/**
 * Created by nick on 2/24/16.
 *
 * Bridge component between non-UI Bluetooth threads and the UI. Handles individual events with arguments
 * and acts on the UI accordingly.
 */
class BluetoothMessageHandler {

	private enum Event {
		MESSAGE_LISTENING,
		MESSAGE_STOPPED,
		MESSAGE_CONNECTING_TO_DEVICE,
		MESSAGE_CONNECTED_TO_DEVICE,
		MESSAGE_CONNECTION_FAILED,
		MESSAGE_CONNECTION_LOST,
		MESSAGE_READ,
		MESSAGE_WRITE
	}

	private Callback mCallback;

	private BluetoothDevice mCurrentDevice;

	interface Callback {
		void onBluetoothListening();
		void onBluetoothStopped(@Nullable BluetoothDevice device);
		void onBluetoothConnectingToDevice(@NonNull BluetoothDevice device);
		void onBluetoothDeviceConnected(BluetoothDevice device);
		void onBluetoothConnectionFailed(BluetoothDevice device);
		void onBluetoothConnectionLost(@Nullable BluetoothDevice device);
		void onBluetoothMessageReceived(BluetoothDevice fromDevice, String message);
	}

	BluetoothMessageHandler() {

	}

	void onBluetoothListening() {
		Message message = mInternalHandler.obtainMessage(Event.MESSAGE_LISTENING.ordinal());
		message.sendToTarget();
	}

	void onBluetoothStopped() {
		mCurrentDevice = null;
		Message message = mInternalHandler.obtainMessage(Event.MESSAGE_STOPPED.ordinal());
		message.sendToTarget();
	}

	void onBluetoothConnectingToDevice(BluetoothDevice bluetoothDevice) {
		mCurrentDevice = bluetoothDevice;
		Message message = mInternalHandler.obtainMessage(Event.MESSAGE_CONNECTING_TO_DEVICE.ordinal());
		message.sendToTarget();
	}

	void onBluetoothDeviceConnected(BluetoothDevice bluetoothDevice) {
		mCurrentDevice = bluetoothDevice;
		Message message = mInternalHandler.obtainMessage(Event.MESSAGE_CONNECTED_TO_DEVICE.ordinal());
		message.sendToTarget();
	}

	void onConnectionFailed() {
		mCurrentDevice = null;
		Message message = mInternalHandler.obtainMessage(Event.MESSAGE_CONNECTION_FAILED.ordinal());
		message.sendToTarget();
	}

	void onConnectionLost() {
		Message message = mInternalHandler.obtainMessage(Event.MESSAGE_CONNECTION_LOST.ordinal());
		message.sendToTarget();
	}

	void onMessageReceived(int count, byte[] buffer) {
		mInternalHandler.obtainMessage(
				Event.MESSAGE_READ.ordinal(), count, -1, buffer).sendToTarget();
	}

	void onMessageWritten(byte[] bytes) {
		// Share the sent message back to the UI Activity
		mInternalHandler.obtainMessage(Event.MESSAGE_WRITE.ordinal(), -1, -1, bytes).sendToTarget();
	}

	public void setCallback(Callback callback) {
		mCallback = callback;
	}

	private final Handler mInternalHandler = new Handler(Looper.getMainLooper()) {
		@Override
		public void handleMessage(Message msg) {
			Event event = Event.values()[msg.what];

			switch (event) {
				case MESSAGE_LISTENING:
					if (BuildConfig.DEBUG) {
						Log.i(getClass().getSimpleName(), "BLUETOOTH LISTENING");
					}

					mCallback.onBluetoothListening();
					break;
				case MESSAGE_STOPPED:
					mCallback.onBluetoothStopped(mCurrentDevice);
					break;
				case MESSAGE_CONNECTING_TO_DEVICE:
					if (mCurrentDevice != null) {
						mCallback.onBluetoothConnectingToDevice(mCurrentDevice);
					}

					break;
				case MESSAGE_CONNECTED_TO_DEVICE:
					mCallback.onBluetoothDeviceConnected(mCurrentDevice);
					break;
				case MESSAGE_CONNECTION_LOST:
					mCallback.onBluetoothConnectionLost(mCurrentDevice);
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

					mCallback.onBluetoothMessageReceived(mCurrentDevice, readMessage);
					break;
				case MESSAGE_CONNECTION_FAILED:
					mCallback.onBluetoothConnectionFailed(mCurrentDevice);
					break;
			}
		}
	};
}
