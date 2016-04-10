package org.empyrn.darkknight.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.widget.Toast;

import org.empyrn.darkknight.BuildConfig;
import org.empyrn.darkknight.GameMode;
import org.empyrn.darkknight.R;
import org.empyrn.darkknight.gamelogic.AbstractGameController;
import org.empyrn.darkknight.gamelogic.Game;
import org.empyrn.darkknight.gamelogic.Move;
import org.empyrn.darkknight.gamelogic.TextIO;

public class BluetoothGameController extends AbstractGameController implements BluetoothMessageHandler.Callback {
	private final Context mContext;

	private final BluetoothAdapter mBluetoothAdapter;
	private BluetoothGameEventListener mBluetoothGameEventListener = null;
	private Game game;

	private GameMode mGameMode;

	private int mCurrentState;


	public BluetoothGameController(Context context) {
		this(context, BluetoothAdapter.getDefaultAdapter());
	}

	public BluetoothGameController(Context context, BluetoothAdapter bluetoothAdapter) {
		mContext = context;
		mBluetoothAdapter = bluetoothAdapter;
		mGameMode = null;

		if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
			throw new IllegalStateException("Cannot create Bluetooth game without enabled Bluetooth controller");
		}

		setupBluetoothService();
	}

	public void setDiscoverable(Context context) {
		if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			Intent discoverableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(
					BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			context.startActivity(discoverableIntent);
		} else if (getGui() != null) {
			getGui().showSnackbar(mContext.getString(
					R.string.this_device_is_already_discoverable), Snackbar.LENGTH_SHORT);
		} else {
			Toast.makeText(mContext, mContext.getString(
					R.string.this_device_is_already_discoverable), Toast.LENGTH_SHORT).show();
		}
	}

	@Nullable
	@Override
	public Game getGame() {
		return game;
	}

	@Override
	protected String getStatusText() {
		return null;
	}

	@Override
	public GameMode getGameMode() {
		return mGameMode;
	}

	@Override
	public void setGameMode(GameMode gameMode) {
		this.mGameMode = gameMode;
	}

	public void connectToDevice(String address) {
		connectToDevice(mBluetoothAdapter.getRemoteDevice(address));
	}

	public void connectToDevice(BluetoothDevice device) {
		mBluetoothGameEventListener.connect(device);
	}

	public void setupBluetoothService() {
		if (BuildConfig.DEBUG) {
			Log.d(getClass().getSimpleName(), "setupBluetoothService()");
		}

		BluetoothMessageHandler handler = new BluetoothMessageHandler();
		handler.setCallback(this);

		// initialize the BluetoothGameEventListener to perform Bluetooth connections
		mBluetoothGameEventListener = new BluetoothGameEventListener(mBluetoothAdapter, handler);
		mBluetoothGameEventListener.startListening();
	}

	@Override
	public void destroyGame() {
		stopBluetoothService();

		if (getGui() != null) {
			getGui().onGameStopped();
		}
	}

	public void stopBluetoothService() {
		if (mBluetoothGameEventListener == null) {
			return;
		}

		mBluetoothGameEventListener.stopListening();
		mBluetoothGameEventListener = null;
	}

	/**
	 * Sends a message.
	 *
	 * @param message A string of text to send over Bluetooth.
	 */
	private void sendMessage(String message) {
		// Check that we're actually connected before trying anything
		if (mBluetoothGameEventListener.getState() != BluetoothGameEventListener.State.STATE_CONNECTED) {
			Toast.makeText(mContext, R.string.not_connected_to_another_player, Toast.LENGTH_SHORT).show();
			return;
		}

		// check that there's actually something to send
		if (message.length() > 0) {
			// Get the message bytes and tell the BluetoothGameEventListener to write
			byte[] dataToSend = message.getBytes();
			mBluetoothGameEventListener.write(dataToSend);
		}
	}

	@Override
	public void startNewGame() {
		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "Starting new Bluetooth game with mode " + mGameMode);
		}

		if (mGameMode == null) {
			throw new IllegalStateException("Must set a game mode to start a new game");
		}

		game = new Game(getGameTextListener(), Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE);

		if (getGui() != null) {
			getGui().onNewGameStarted();
		}
	}

	@Override
	public void restoreGame(GameMode gameMode, byte[] state) {
		// not supported yet
		throw new UnsupportedOperationException();
	}

	@Override
	public void resume() {
		if (getGui() != null) {
			getGui().onGameResumed();
		}
	}

	public void sendMove(Move m) {
		this.sendMessage(TextIO.moveToUCIString(m));
	}

	@Override
	public void tryPlayMove(Move m) {
		if (!isPlayerTurn() || getGui() == null) {
			throw new IllegalStateException();
		}

		if (doMove(m)) {
			sendMove(m);
			onMoveMade();
		}
	}

	@Override
	public void pause() {
		if (mBluetoothGameEventListener != null) {
			mBluetoothGameEventListener.reset();
		}
	}

	@Override
	public void resignGame() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (getGame().getGameStatus() == Game.Status.ALIVE) {
			getGame().processString("resign");
			sendMessage("resign");
			onMoveMade();
		}
	}

//	@Override
//	public void onBluetoothStateChange(@Nullable BluetoothDevice remoteDevice, int code) {
//		if (getGui() == null) {
//			return;
//		}
//
//		switch (code) {
//			case BluetoothGameEventListener.STATE_CONNECTED:
//				if (getGameMode() != null) {
//					getGui().dismissSnackbar();
//
//					startNewGame();
//
//					if (getGameMode() == GameMode.PLAYER_WHITE) {
//						BluetoothGameController.this.sendMessage("iplaywhite");
//					} else {
//						BluetoothGameController.this.sendMessage("iplayblack");
//					}
//
//					resume();
//				} else {
//					getGui().showSnackbar(mContext.getString(R.string.connected_to_bluetooth_device),
//							Snackbar.LENGTH_INDEFINITE);
//				}
//
//				break;
//			case BluetoothGameEventListener.STATE_CONNECTING:
//				getGui().showSnackbar(mContext.getString(R.string.connecting_to_bluetooth_device),
//						Snackbar.LENGTH_INDEFINITE);
//				break;
//			case BluetoothGameEventListener.STATE_LISTEN:
//				getGui().showSnackbar(mContext.getString(R.string.waiting_for_a_bluetooth_opponent_to_connect),
//						Snackbar.LENGTH_INDEFINITE);
//				break;
//			case BluetoothGameEventListener.STATE_LOST_CONNECTION:
//				if (isGameActive()) {
//					getGui().showSnackbar(
//							mContext.getString(R.string.bluetooth_connection_to_device_lost),
//							Snackbar.LENGTH_INDEFINITE);
//					pause();
//				}
//				break;
//			case BluetoothGameEventListener.STATE_NONE:
//				// mTitle.setText(R.string.title_not_connected);
//				break;
//		}
//	}

	@Override
	public void onBluetoothListening() {
		if (getGui() == null) {
			return;
		}

		getGui().showSnackbar(mContext.getString(R.string.waiting_for_a_bluetooth_opponent_to_connect),
				Snackbar.LENGTH_INDEFINITE);
	}

	@Override
	public void onBluetoothStopped() {

	}

	@Override
	public void onBluetoothConnectingToDevice(BluetoothDevice device) {
		if (getGui() == null) {
			return;
		}

		getGui().showSnackbar(mContext.getString(R.string.connecting_to_bluetooth_device),
				Snackbar.LENGTH_INDEFINITE);
	}

	@Override
	public void onBluetoothMessageReceived(BluetoothDevice fromDevice, String readMessage) {
		if (readMessage.startsWith("iplay")) {
			if (mGameMode != null) {
				Toast.makeText(mContext,
						R.string.your_opponent_tried_to_start_a_new_game,
						Toast.LENGTH_SHORT).show();
				return;
			}

			if (readMessage.equals("iplaywhite")) {
				setGameMode(GameMode.PLAYER_BLACK);
			} else if (readMessage.equals("iplayblack")) {
				setGameMode(GameMode.PLAYER_WHITE);
			}

			if (getGui() != null) {
				getGui().dismissSnackbar();
			}

			// begin a new game
			startNewGame();

			return;
		}

		if (isPlayerTurn()) {
			Toast.makeText(mContext, R.string.your_opponent_attempted_to_make_a_move_during_your_turn,
					Toast.LENGTH_SHORT).show();
			return;
		}

		if (readMessage.equals("resign")) {
			resignGame();
			destroyGame();
			return;
		}

		// make the move from Bluetooth
		Move m = TextIO.UCIstringToMove(readMessage);

		if (m != null) {
			if (doMove(m)) {
				onMoveMade();
			} else {
				Toast.makeText(mContext, R.string.opponent_attemped_to_cheat_so_the_game_was_ended,
						Toast.LENGTH_SHORT).show();
				destroyGame();
			}
		} else {
			Toast.makeText(mContext, R.string.an_unrecognized_move_was_played, Toast.LENGTH_SHORT).show();
			destroyGame();
		}
	}

	@SuppressWarnings("ConstantConditions")
	@Override
	public void onBluetoothDeviceConnected(BluetoothDevice device) {
		if (getGameMode() != null) {
			getGui().dismissSnackbar();

			startNewGame();

			if (getGameMode() == GameMode.PLAYER_WHITE) {
				BluetoothGameController.this.sendMessage("iplaywhite");
			} else {
				BluetoothGameController.this.sendMessage("iplayblack");
			}

			resume();
		} else {
			getGui().showSnackbar(mContext.getString(R.string.connected_to_bluetooth_device),
					Snackbar.LENGTH_INDEFINITE);
		}
	}

	@SuppressWarnings("ConstantConditions")
	@Override
	public void onBluetoothConnectionFailed(BluetoothDevice device) {
		setGameMode(null);
		getGui().showSnackbar(mContext.getString(R.string.connection_to_bluetooth_device_failed),
				Snackbar.LENGTH_LONG);
	}

	@Override
	public void onBluetoothConnectionLost(BluetoothDevice device) {
		if (isGameActive()) {
			if (getGui() != null) {
				getGui().showSnackbar(
						mContext.getString(R.string.bluetooth_connection_to_device_lost),
						Snackbar.LENGTH_INDEFINITE);
			}

			pause();
		}
	}
}
