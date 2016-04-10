package org.empyrn.darkknight;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.ResultReceiver;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Random;

/**
 * Created by nick on 10/5/15.
 */
public class PGNDownloadService extends IntentService {
	public static final int UPDATE_PROGRESS = 8344;

	public PGNDownloadService() {
		super("PGNDownloadService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		String urlToDownload = intent.getStringExtra("url");
		ResultReceiver receiver = intent.getParcelableExtra("receiver");

		final String downloadFilename = DarkKnightActivity.PGN_DIR + File.separator + "Game" + new Random().nextInt() + ".pgn";

		System.err.println("Downloading " + urlToDownload + " to "
				+ downloadFilename);

		try {
			File pgnDir = new File(DarkKnightActivity.PGN_DIR);

			//noinspection ResultOfMethodCallIgnored
			pgnDir.mkdirs();

			if (!pgnDir.exists()) {
				System.err.println(pgnDir + " doesn't exist");
				return;
			}

			URL url = new URL(urlToDownload);
			URLConnection connection = url.openConnection();
			connection.connect();
			// this will be useful so that you can show a typical 0-100% progress bar
			int fileLength = connection.getContentLength();

			// download the file
			InputStream input = new BufferedInputStream(connection.getInputStream());

			OutputStream output = new FileOutputStream(downloadFilename);

			byte data[] = new byte[1024];
			long total = 0;
			int count;
			while ((count = input.read(data)) != -1) {
				total += count;
				// publishing the progress....
				Bundle resultData = new Bundle();
				resultData.putInt("progress", (int) (total * 100 / fileLength));
				//receiver.send(UPDATE_PROGRESS, resultData);
				output.write(data, 0, count);
			}

			output.flush();
			output.close();
			input.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		Bundle resultData = new Bundle();
		resultData.putInt("progress", 100);
		resultData.putString("pgnFilename", downloadFilename);
		receiver.send(UPDATE_PROGRESS, resultData);
	}
}
