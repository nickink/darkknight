package org.empyrn.darkknight.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.util.Pair;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.empyrn.darkknight.R;
import org.empyrn.darkknight.view.DividerItemDecoration;

import java.util.ArrayList;
import java.util.Set;

/**
 * This Activity appears as a dialog. It lists any paired devices and
 * devices detected in the area after discovery. When a device is chosen
 * by the user, the MAC address of the device is sent back to the parent
 * Activity in the result Intent.
 */
public class DeviceListActivity extends AppCompatActivity {
	// Debugging
	private static final String TAG = "DeviceListActivity";
	private static final boolean D = true;

	// Return Intent extra
	public static final String EXTRA_DEVICE_ADDRESS = "device_address";

	// Member fields
	private BluetoothAdapter mBtAdapter;

	private DeviceListAdapter mAdapter;

	private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 28291;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.device_list);

		setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

		// set result CANCELED in case the user backs out
		setResult(Activity.RESULT_CANCELED);

		mAdapter = new DeviceListAdapter();

		RecyclerView mDeviceListView = (RecyclerView) findViewById(R.id.device_list_view);
		mDeviceListView.setLayoutManager(new LinearLayoutManager(this));
		mDeviceListView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL_LIST));
		mDeviceListView.setAdapter(mAdapter);
		mDeviceListView.setFocusable(true);

		this.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED));

		// register for broadcasts when a device is discovered
		this.registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));

		// register for broadcasts when discovery has finished
		this.registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));

		// get the local Bluetooth adapter
		mBtAdapter = BluetoothAdapter.getDefaultAdapter();

		if (mBtAdapter.isDiscovering()) {
			mBtAdapter.cancelDiscovery();
		}

		// get a set of currently paired devices
		Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

		for (BluetoothDevice device : pairedDevices) {
			mAdapter.addPairedDevice(new Pair<>(device.getName(), device.getAddress()));
		}
	}

	private static class DeviceItemViewHolder extends RecyclerView.ViewHolder {
		final TextView headerView;
		final TextView subtitleView;

		public DeviceItemViewHolder(View itemView) {
			super(itemView);
			headerView = (TextView) itemView.findViewById(android.R.id.text1);
			subtitleView = (TextView) itemView.findViewById(android.R.id.text2);
		}
	}

	private class DeviceListAdapter extends RecyclerView.Adapter<DeviceItemViewHolder> {
		final ArrayList<Pair<String, String>> mPairedDevices = new ArrayList<>();
		final ArrayList<Pair<String, String>> mNewDevices = new ArrayList<>();

		DeviceListAdapter() {
			setHasStableIds(true);
		}

		@Override
		public DeviceItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			return new DeviceItemViewHolder(LayoutInflater.from(DeviceListActivity.this)
					.inflate(R.layout.bluetooth_device_item, parent, false));
		}

		@Override
		public void onBindViewHolder(final DeviceItemViewHolder holder, final int position) {
			Pair<String, String> deviceDetails = getDeviceDetailsAt(position);
			holder.headerView.setText(deviceDetails.first);
			holder.subtitleView.setText(deviceDetails.second);
			holder.itemView.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					onDeviceSelected(getDeviceDetailsAt(holder.getAdapterPosition()).second);
				}
			});
		}

		@Override
		public int getItemCount() {
			return mPairedDevices.size() + mNewDevices.size();
		}

		public void addNewDevice(Pair<String, String> newDevice) {
			if (!mNewDevices.contains(newDevice)) {
				mNewDevices.add(newDevice);
			}

			notifyDataSetChanged();
		}

		public void addPairedDevice(Pair<String, String> newDevice) {
			mPairedDevices.add(newDevice);
			notifyDataSetChanged();
		}

		private Pair<String, String> getDeviceDetailsAt(int index) {
			if (index < mPairedDevices.size()) {
				return mPairedDevices.get(index);
			} else {
				return mNewDevices.get(index - mPairedDevices.size());
			}
		}

		@Override
		public long getItemId(int position) {
			// TODO actually fix this
			return getDeviceDetailsAt(position).second.hashCode();
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		// make sure we're not doing discovery anymore
		if (mBtAdapter != null) {
			mBtAdapter.cancelDiscovery();
		}

		// unregister broadcast listeners
		this.unregisterReceiver(mReceiver);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		switch (requestCode) {
			case REQUEST_COARSE_LOCATION_PERMISSIONS: {
				if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					doDiscovery();
				} else {
					Toast.makeText(this, R.string.no_permission_to_scan_bluetooth_devices, Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	/**
	 * Start device discovery with the BluetoothAdapter
	 */
	private void doDiscovery() {
		if (D) {
			Log.d(TAG, "doDiscovery()");
		}

		int hasPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);
		if (hasPermission != PackageManager.PERMISSION_GRANTED) {
			// request the permission to perform the Bluetooth scan
			ActivityCompat.requestPermissions(this,
					new String[]{
							android.Manifest.permission.ACCESS_COARSE_LOCATION},
					REQUEST_COARSE_LOCATION_PERMISSIONS);
			return;
		}

		// if we're already discovering, cancel
		if (mBtAdapter.isDiscovering()) {
			mBtAdapter.cancelDiscovery();
		}

		getSupportActionBar().setSubtitle(getString(R.string.bluetooth_scanning));

		// request discover from BluetoothAdapter
		mBtAdapter.startDiscovery();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.bluetooth_scan_menu, menu);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.item_bluetooth_scan).setVisible(false);
		//menu.findItem(R.id.item_bluetooth_scan).setVisible(!mBtAdapter.isDiscovering());

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.item_bluetooth_scan) {
			doDiscovery();
			ActivityCompat.invalidateOptionsMenu(this);
			return true;
		} else if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	/**
	 * Callback when a device is selected.
	 *
	 * @param address device MAC address
	 */
	protected void onDeviceSelected(final String address) {
		// Cancel discovery because it's costly and we're about to connect
		mBtAdapter.cancelDiscovery();

		// Create the result Intent and include the MAC address
		Intent intent = new Intent();
		intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

		// Set result and finish this Activity
		setResult(Activity.RESULT_OK, intent);
		finish();
	}

	// the BroadcastReceiver that listens for discovered devices
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			// when discovery finds a device
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// get the BluetoothDevice object from the intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

				// if it's already paired, skip it, because it's been listed already
				if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
					String deviceName = (device.getName() == null || device.getName().trim().isEmpty())
							? getString(R.string.bluetooth_device_no_name) : device.getName();
					mAdapter.addNewDevice(new Pair<>(deviceName, device.getAddress()));
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
				if (mAdapter.mNewDevices.isEmpty()) {
					Toast.makeText(DeviceListActivity.this,
							getResources().getString(R.string.bluetooth_no_devices_found),
							Toast.LENGTH_LONG).show();
				}

				getSupportActionBar().setSubtitle(null);
				ActivityCompat.invalidateOptionsMenu(DeviceListActivity.this);
			} else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
				ActivityCompat.invalidateOptionsMenu(DeviceListActivity.this);
			}
		}
	};
}
