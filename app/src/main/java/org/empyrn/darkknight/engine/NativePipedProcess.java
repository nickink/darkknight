package org.empyrn.darkknight.engine;

import android.os.Looper;
import android.util.Log;

import org.empyrn.darkknight.BuildConfig;

public class NativePipedProcess {
	static {
		System.loadLibrary("jni");
	}

	private boolean processAlive;

	NativePipedProcess() {
		processAlive = false;
	}

	/** Start process. */
	public final void initialize() {
		if (!processAlive) {
			startProcess();
			processAlive = true;
		}
	}

	/** Shut down process. */
	public final void shutDown() {
		if (processAlive) {
			writeLineToProcess("quit", true);
			processAlive = false;
		}
	}

	public final boolean isProcessAlive() {
		return processAlive;
	}

	/**
	 * Read a line from the process.
	 * @param timeoutMillis Maximum time to wait for data
	 * @return The line, without terminating newline characters,
	 *         or empty string if no data available,
	 *         or null if I/O error.
	 */
	public final String readLineFromProcess(int timeoutMillis) {
		String ret = readFromProcess(timeoutMillis);
		if (ret == null) {
			return null;
		}

		if (ret.length() > 0) {
			Log.d("DK.UCI (" + this.toString() + ") -> Controller", ret);
		}

		return ret;
	}

	public final synchronized void writeLineToProcess(String data) {
		writeLineToProcess(data, false);
	}

	/** Write a line to the process. \n will be added automatically. */
	private synchronized void writeLineToProcess(String data, boolean isInterrupt) {
//		if (!isInterrupt && Looper.getMainLooper().equals(Looper.myLooper())) {
//			Log.w("UCI", "UCI executing instruction " + data + " on the main thread -- this is not recommended");
//			throw new IllegalStateException();
//		}

		if (BuildConfig.DEBUG) {
			Log.i("DK.Controller -> UCI (" + this.toString() + ")", data);
		}

		writeToProcess(data + "\n");
	}

	/** Start the child process. */
	private native void startProcess();

	/**
	 * Read a line of data from the process.
	 * Return as soon as there is a full line of data to return, 
	 * or when timeoutMillis milliseconds have passed.
	 */
	private native String readFromProcess(int timeoutMillis);

	/** Write data to the process. */
	private native void writeToProcess(String data);
}
