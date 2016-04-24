package org.empyrn.darkknight.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.support.annotation.NonNull;
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

	private boolean isGameResumed = false;

	@Deprecated
	private static BluetoothGameController mLastInstance;


	public BluetoothGameController(Context context) {
		this(context, BluetoothAdapter.getDefaultAdapter());
	}

	public BluetoothGameController(Context context, BluetoothAdapter bluetoothAdapter) {
		mContext = context;
		mBluetoothAdapter = bluetoothAdapter;
		mGameMode = null;

		mLastInstance = this;

		if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
			throw new IllegalStateException("Cannot create Bluetooth game without enabled Bluetooth controller");
		}

		setupBluetoothService();
	}

	@Deprecated
	public static synchronized BluetoothGameController getLastInstance(Context context) {
		if (mLastInstance == null) {
			mLastInstance = new BluetoothGameController(context);
		}

		return mLastInstance;
	}

	@Nullable
	@Override
	public Game getGame() {
		return game;
	}

	@Override
	public String getStatusText() {
		if (game == null) {
			return null;
		}

		String str = Integer.valueOf(game.currPos().fullMoveCounter).toString();
		str += game.currPos().whiteMove ? ". White's move" : "... Black's move";

		if (game.getGameStatus() != Game.Status.ALIVE) {
			str = game.getGameStateString();
		}

		return str;
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
	public void stopGame(boolean withCallback) {
		if (isGameResumed()) {
			pauseGame();
		}

		stopBluetoothService();
		game = null;

		if (withCallback) {
			postEvent(new Runnable() {
				@Override
				public void run() {
					getGui().onGameStopped();
				}
			});
		}
	}

	@Override
	public boolean isGameActive() {
		return super.isGameActive()
				&& mBluetoothGameEventListener != null
				&& mBluetoothGameEventListener.getState() == BluetoothGameEventListener.State.STATE_CONNECTED;
	}

	public void stopBluetoothService() {
		mLastInstance = null;

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
			// get the message bytes and tell the BluetoothGameEventListener to write
			byte[] dataToSend = message.getBytes();
			mBluetoothGameEventListener.write(dataToSend);
		}
	}

	@Override
	public void startGame() {
		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "Starting new Bluetooth game with mode " + mGameMode);
		}

		if (mGameMode == null) {
			throw new IllegalStateException("Must set a game mode to start a new game");
		}

		game = new Game(getGameTextListener(), Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE);

		getGui().onGameStarted();
	}

	@Override
	public void restoreGame(GameMode gameMode, byte[] state) {
		if (getGame() == null) {
			return;
		}

		// the game state can't actually be restored directly from Bluetooth, but if this is a
		// "last instance" Bluetooth controller, it can be resumed at least
		updateMoveList();

		postEvent(new Runnable() {
			@Override
			public void run() {
				getGui().onGameRestored();
			}
		});
	}

	@Override
	public void resumeGame() {
		if (isGameStarting() || isConnected()) {
			throw new IllegalStateException("Game is already starting");
		} else if (isGameResumed) {
			throw new IllegalStateException("Game is already resumed");
		}

		isGameResumed = true;
		postEvent(new Runnable() {
			@Override
			public void run() {
				getGui().onGameResumed();
			}
		});
	}

	public void sendMove(Move m) {
		this.sendMessage(TextIO.moveToUCIString(m));
	}

	@Override
	public void tryPlayMove(Move m) {
		if (!isPlayerTurn()) {
			throw new IllegalStateException("Cannot play move during player turn");
		}

		if (doMove(m)) {
			sendMove(m);
			onMoveMade();
		}
	}

	@Override
	protected void onGameStatusChanged(Game.Status status) {
		if (status != Game.Status.ALIVE) {
			isGameResumed = false;
		}
	}

	@Override
	public boolean isGameStarting() {
		return mBluetoothGameEventListener != null && mBluetoothGameEventListener.getState()
				== BluetoothGameEventListener.State.STATE_CONNECTING;
	}

	@Override
	public void pauseGame() {
		isGameResumed = false;
		// not really possible to implement over Bluetooth, although a temporarily lost connection
		// is equivalent
		postEvent(new Runnable() {
			@Override
			public void run() {
				getGui().onGamePaused();
			}
		});
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

	@Override
	public boolean isGameResumed() {
		return isGameResumed;
	}

	@Override
	public boolean canResumeGame() {
		return false;
	}

	@Override
	public boolean isAnalyzing() {
		return false;
	}

	@Override
	public boolean isAnalysisQuickPause() {
		return false;
	}

	@Override
	public void acceptDrawOffer() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (getGame().getGameStatus() == Game.Status.ALIVE) {
			getGame().processString("draw accept");
			sendMessage("draw accept");
			onMoveMade();
		}
	}

	@Override
	public boolean isOpponentThinking() {
		return isGameActive() && !isPlayerTurn();
	}

	@Override
	public void declineDrawOffer() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (getGame().getGameStatus() == Game.Status.ALIVE) {
			sendMessage("draw decline");
			onMoveMade();
		}
	}

	public boolean isListening() {
		return mBluetoothGameEventListener != null && mBluetoothGameEventListener.getState()
				== BluetoothGameEventListener.State.STATE_LISTEN;
	}

	public boolean isConnected() {
		return mBluetoothGameEventListener != null && mBluetoothGameEventListener.getState()
				== BluetoothGameEventListener.State.STATE_CONNECTED;
	}

	@Override
	public void onBluetoothListening() {
		getGui().onWaitingForOpponent();
	}

	@Override
	public void onBluetoothStopped(BluetoothDevice device) {
		// Bluetooth is stopped
	}

	@Override
	public void onBluetoothConnectingToDevice(@NonNull BluetoothDevice device) {
		getGui().showMessage(mContext.getString(R.string.connecting_to_bluetooth_device, device.getName()),
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

			getGui().dismissMessage();

			// begin a new game
			startGame();

			return;
		}

		if (isPlayerTurn()) {
			Toast.makeText(mContext, R.string.your_opponent_attempted_to_make_a_move_during_your_turn,
					Toast.LENGTH_SHORT).show();
			stopGame();
			return;
		}

		if (readMessage.equals("resign")) {
			resignGame();
			stopGame();
			return;
		}

		if (readMessage.equals("draw decline")) {
			getGui().showMessage(mContext.getString(R.string.draw_offer_declined), Snackbar.LENGTH_LONG);
			return;
		}

		if (readMessage.startsWith("draw offer ")) {
			game.processString(readMessage);

			Move m = TextIO.UCIstringToMove(readMessage.substring("draw offer ".length()));

			getGui().onOpponentOfferDraw(m);

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
				stopGame();
			}
		} else {
			Toast.makeText(mContext, R.string.an_unrecognized_move_was_played, Toast.LENGTH_SHORT).show();
			stopGame();
		}
	}

	@Override
	public void onBluetoothDeviceConnected(BluetoothDevice device) {
		if (getGameMode() != null) {
			getGui().dismissMessage();

			startGame();

			if (getGameMode() == GameMode.PLAYER_WHITE) {
				BluetoothGameController.this.sendMessage("iplaywhite");
			} else {
				BluetoothGameController.this.sendMessage("iplayblack");
			}
		} else {
			getGui().onConnectedToOpponent(mContext.getString(R.string.connected_to_bluetooth_device, device.getName()));
		}
	}

	@SuppressWarnings("ConstantConditions")
	@Override
	public void onBluetoothConnectionFailed(BluetoothDevice device) {
		setGameMode(null);
		Toast.makeText(mContext, mContext.getString(R.string.connection_to_bluetooth_device_failed), Toast.LENGTH_LONG).show();
	}

	@Override
	public void onBluetoothConnectionLost(@Nullable BluetoothDevice device) {
		if (super.isGameActive()) {
			getGui().onOpponentDisconnected(device != null ? device.getName() : null);
		} else {
			// game was stopped by user, presumably?
			game = null;
			getGui().onGameStopped();
		}
	}
}
