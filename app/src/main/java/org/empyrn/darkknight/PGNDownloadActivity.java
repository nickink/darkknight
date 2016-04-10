package org.empyrn.darkknight;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

/**
 * Created by nick on 10/5/15.
 */
public class PGNDownloadActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (savedInstanceState == null) {
			String urlToDownload = getIntent().getDataString();

			Intent i = new Intent(this, PGNDownloadService.class);
			i.putExtra("url", urlToDownload);
			i.putExtra("receiver", new DownloadReceiver(new Handler()));
			startService(i);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == DarkKnightActivity.RESULT_LOAD_PGN) {
			Intent i = new Intent(this, DarkKnightActivity.class);
			i.putExtra("pgnData", data.getAction());
			startActivity(i);
			finish();
		}

		super.onActivityResult(requestCode, resultCode, data);
	}

	private class DownloadReceiver extends ResultReceiver {
		public DownloadReceiver(Handler handler) {
			super(handler);
		}

		@Override
		protected void onReceiveResult(int resultCode, Bundle resultData) {
			super.onReceiveResult(resultCode, resultData);
			if (resultCode == PGNDownloadService.UPDATE_PROGRESS) {
				int progress = resultData.getInt("progress");
				if (progress == 100) {
					final String pgnFilename = resultData.getString("pgnFilename");

					System.err.println("PGN filename: " + pgnFilename);

					if (pgnFilename != null) {
						Intent i = new Intent(PGNDownloadActivity.this,
								LoadPGNActivity.class);
						i.setAction(pgnFilename);
						startActivityForResult(i, DarkKnightActivity.RESULT_LOAD_PGN);
					} else {
						Toast.makeText(PGNDownloadActivity.this, "Loading PGN failed", Toast.LENGTH_LONG).show();
					}
				}
			}
		}
	}
}
