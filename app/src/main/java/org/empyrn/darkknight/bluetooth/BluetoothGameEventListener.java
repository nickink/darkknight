package org.empyrn.darkknight.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.empyrn.darkknight.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for incoming
 * connections, a thread for connecting with a device, and a thread for
 * performing data transmissions when connected.
 */
public class BluetoothGameEventListener {
	// debugging tag
	private static final String TAG = "BluetoothEventListener";

	// name for the SDP record when creating server socket
	private static final String NAME = "Dark Knight Bluetooth Game Controller";

	// unique UUID for Dark Knight chess service
	private static final UUID DARK_KNIGHT_GAME_CONTROLLER_UUID = UUID.fromString("72caefa0-568b-11e0-b8af-0800200c9a66");

	// member fields
	private final BluetoothAdapter mAdapter;
	private final BluetoothMessageHandler mHandler;
	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private State mState;

	// constants that indicate the current connection state
	public enum State {
		STATE_NONE,             // doing nothing
		STATE_LISTEN,           // listening for incoming connections
		STATE_CONNECTING,       // now initiating an outgoing connection
		STATE_CONNECTED,        // now connected to a remote device
		STATE_LOST_CONNECTION   // lost connection
	}

	/**
	 * Constructor. Prepares a new BluetoothGameController session.
	 *
	 * @param handler A Handler to send messages back to the UI Activity
	 */
	public BluetoothGameEventListener(BluetoothAdapter adapter, BluetoothMessageHandler handler) {
		mAdapter = adapter;
		mState = State.STATE_NONE;
		mHandler = handler;
	}

	/**
	 * Set the current state of the chat connection
	 *
	 * @param state A value defining the current connection state
	 */
	private synchronized void setState(State state) {
		if (BuildConfig.DEBUG) {
			Log.d(TAG, "setState() " + mState + " -> " + state);
		}

		mState = state;
	}

	/**
	 * Return the current connection state.
	 */
	public synchronized State getState() {
		return mState;
	}

	public synchronized void startListening() {
		if (BuildConfig.DEBUG) {
			Log.d(TAG, "startListening");
		}

		// cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// start the thread to listen on a BluetoothServerSocket
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}

		setState(State.STATE_LISTEN);
		mHandler.onBluetoothListening();
	}

	/**
	 * Reset the service.
	 */
	public synchronized void reset() {
		// cancel any thread attempting to make a connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// start the thread to listen on a BluetoothServerSocket
		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}

		setState(State.STATE_LISTEN);
		mHandler.onBluetoothListening();
	}

	/**
	 * Start the ConnectThread to initiate a connection to a remote device.
	 *
	 * @param device The BluetoothDevice to connect
	 */
	public synchronized void connect(BluetoothDevice device) {
		if (BuildConfig.DEBUG) {
			Log.d(TAG, "connect to: " + device);
		}

		// cancel any thread attempting to make a connection
		if (mState == State.STATE_CONNECTING) {
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		}

		// cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// start the thread to connect with the given device
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
		setState(State.STATE_CONNECTING);
		mHandler.onBluetoothConnectingToDevice(device);
	}

	/**
	 * Start the ConnectedThread to begin managing a Bluetooth connection
	 *
	 * @param socket The BluetoothSocket on which the connection was made
	 * @param device The BluetoothDevice that has been connected
	 */
	private synchronized void connected(BluetoothSocket socket,
	                                   BluetoothDevice device) {
		if (BuildConfig.DEBUG) {
			Log.d(TAG, "connected");
		}

		// cancel the thread that completed the connection
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		// cancel any thread currently running a connection
		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		// cancel the accept thread because we only want to connect to one
		// device
		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}

		// start the thread to manage the connection and perform transmissions
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();

		// send the name of the connected device back to the UI Activity
		setState(State.STATE_CONNECTED);
		mHandler.onBluetoothDeviceConnected(device);
	}

	/**
	 * Stop all threads
	 */
	public synchronized void stopListening() {
		if (BuildConfig.DEBUG) {
			Log.d(TAG, "stopListening");
		}

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}

		setState(State.STATE_NONE);
		mHandler.onBluetoothStopped();
	}

	/**
	 * Write to the ConnectedThread without synchronization
	 *
	 * @param out The bytes to write
	 * @see ConnectedThread#write(byte[])
	 */
	public void write(byte[] out) {
		// create temporary object
		ConnectedThread r;

		// synchronize a copy of the ConnectedThread
		synchronized (this) {
			if (mState != State.STATE_CONNECTED)
				return;
			r = mConnectedThread;
		}

		// perform the write without synchronization
		r.write(out);
	}

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 */
	private void connectionFailed(BluetoothDevice device) {
		setState(State.STATE_LISTEN);
		mHandler.onConnectionFailed();
	}

	/**
	 * Indicate that the connection was lost and notify the UI Activity.
	 */
	private void connectionLost() {
		setState(State.STATE_LOST_CONNECTION);
		mHandler.onConnectionLost();
	}

	/**
	 * This thread runs while listening for incoming connections. It behaves
	 * like a server-side client. It runs until a connection is accepted (or
	 * until cancelled).
	 */
	private class AcceptThread extends Thread {
		// the local server socket
		private final BluetoothServerSocket mmServerSocket;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;

			// create a new listening server socket
			try {
				tmp = mAdapter
						.listenUsingRfcommWithServiceRecord(NAME, DARK_KNIGHT_GAME_CONTROLLER_UUID);
			} catch (IOException e) {
				Log.e(TAG, "listen() failed", e);
			}

			mmServerSocket = tmp;
		}

		public void run() {
			if (BuildConfig.DEBUG)
				Log.d(TAG, "BEGIN mAcceptThread" + this);
			setName("AcceptThread");
			BluetoothSocket socket;

			// listen to the server socket if we're not connected
			while (mState != BluetoothGameEventListener.State.STATE_CONNECTED) {
				try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, "accept() failed", e);
					break;
				}

				// if a connection was accepted
				if (socket != null) {
					synchronized (BluetoothGameEventListener.this) {
						switch (mState) {
							case STATE_LISTEN:
							case STATE_CONNECTING:
								// Situation normal. Start the connected thread.
								connected(socket, socket.getRemoteDevice());
								break;
							case STATE_NONE:
							case STATE_CONNECTED:
								// Either not ready or already connected. Terminate
								// new socket.
								try {
									socket.close();
								} catch (IOException e) {
									Log.e(TAG, "Could not close unwanted socket", e);
								}
								break;
						}
					}
				}
			}

			if (BuildConfig.DEBUG) {
				Log.i(TAG, "END mAcceptThread");
			}
		}

		public void cancel() {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "cancel " + this);
			}

			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of server failed", e);
			}
		}
	}

	/**
	 * This thread runs while attempting to make an outgoing connection with a
	 * device. It runs straight through; the connection either succeeds or
	 * fails.
	 */
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		public ConnectThread(BluetoothDevice device) {
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				tmp = device.createRfcommSocketToServiceRecord(DARK_KNIGHT_GAME_CONTROLLER_UUID);
			} catch (IOException e) {
				Log.e(TAG, "create() failed", e);
			}
			mmSocket = tmp;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectThread");
			setName("ConnectThread");

			// Always cancel discovery because it will slow down a connection
			mAdapter.cancelDiscovery();

			// Make a connection to the BluetoothSocket
			try {
				// This is a blocking call and will only return on a
				// successful connection or an exception
				mmSocket.connect();
			} catch (IOException e) {
				connectionFailed(mmDevice);

				// close the socket
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG,
							"unable to close() socket during connection failure",
							e2);
				}

				// start the service over to restart listening mode
				BluetoothGameEventListener.this.startListening();
				return;
			}

			// Reset the ConnectThread because we're done
			synchronized (BluetoothGameEventListener.this) {
				mConnectThread = null;
			}

			// Start the connected thread
			connected(mmSocket, mmDevice);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}

	/**
	 * This thread runs during a connection with a remote device. It handles all
	 * incoming and outgoing transmissions.
	 */
	private class ConnectedThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;

		public ConnectedThread(BluetoothSocket socket) {
			Log.d(TAG, "create ConnectedThread");
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			// get the BluetoothSocket input and output streams
			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");
			byte[] buffer = new byte[1024];
			int bytes;

			// keep listening to the input stream while connected
			while (true) {
				try {
					// read from the input stream
					bytes = mmInStream.read(buffer);

					// send the obtained bytes to the UI Activity
					mHandler.onMessageReceived(bytes, buffer);
				} catch (IOException e) {
					Log.e(TAG, "disconnected", e);
					connectionLost();
					break;
				}
			}
		}

		/**
		 * Write to the connected output stream.
		 *
		 * @param buffer The bytes to write
		 */
		public void write(byte[] buffer) {
			try {
				mmOutStream.write(buffer);
				mHandler.onMessageWritten(buffer);
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
}
