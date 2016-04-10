package org.empyrn.darkknight.engine;

import android.util.Log;

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
			writeLineToProcess("quit");
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
			Log.d("DK.UCI -> Controller", ret);
		}

		return ret;
	}

	/** Write a line to the process. \n will be added automatically. */
	public final synchronized void writeLineToProcess(String data) {
		Log.i("DK.Controller -> UCI", data);

//		try {
//			Thread.sleep(1000);
//		} catch (InterruptedException e) {
//			e.printStackTrace();
//		}

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
