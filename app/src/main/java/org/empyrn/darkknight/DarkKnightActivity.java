package org.empyrn.darkknight;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.ClipboardManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.empyrn.darkknight.bluetooth.BluetoothGameController;
import org.empyrn.darkknight.bluetooth.DeviceListActivity;
import org.empyrn.darkknight.engine.ThinkingInfo;
import org.empyrn.darkknight.gamelogic.ChessParseError;
import org.empyrn.darkknight.gamelogic.EngineController;
import org.empyrn.darkknight.gamelogic.Game;
import org.empyrn.darkknight.gamelogic.GameController;
import org.empyrn.darkknight.gamelogic.Move;
import org.empyrn.darkknight.gamelogic.Position;
import org.empyrn.darkknight.gamelogic.PromotionPiece;
import org.empyrn.darkknight.gamelogic.TextIO;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * @author nick
 *         <p/>
 *         Main activity for Dark Knight.
 */
public class DarkKnightActivity extends AppCompatActivity implements GUIInterface {

	static private final int RESULT_EDITBOARD = 0;
	static private final int RESULT_SETTINGS = 1;
	static public final int RESULT_LOAD_PGN = 2;
	static private final int REQUEST_CONNECT_DEVICE = 3;
	static private final int REQUEST_ENABLE_BT = 4;

	// the game controller
	private GameController mGameController;

	private boolean mShowThinking;
	private boolean mShowBookHints;
	private int maxNumArrows;
	private boolean oneTouchMoves;

	private CoordinatorLayout mCoordinatorView;
	private ChessBoardView mChessBoardView;
	private ImageButton mPreviousMoveButton;
	private ImageButton mNextMoveButton;
	private TextView mStatusView;
	private ScrollView moveListScrollView;
	private TextView moveListView;
	private Snackbar mCurrentSnackbar;
	private FloatingActionButton mFab;
	private TextView thinkingInfoView;

	private String variantStr = "";
	private List<Move> variantMoves = null;
	private ThinkingInfo currentThinkingInfo;

	private boolean boardFlippedForAnalysis = false;

	private SharedPreferences mSettings;

	private boolean soundEnabled;
	private MediaPlayer moveSound;

	public static final String BOOK_DIR = "DarkKnight";
	public static final String PGN_DIR = Environment.getExternalStorageDirectory().getAbsolutePath()
			+ File.separator + "DarkKnight" + File.separator + "pgn";

	private long lastVisibleMillis;                 // Time when GUI became invisible. 0 if currently visible.

	private long lastComputationMillis;             // Time when engine last showed that it was computing.

	private boolean canResign;

	static final int MODE_ENGINE = 0;
	static final int MODE_BLUETOOTH = 1;


	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (getResources().getBoolean(R.bool.portrait_only)) {
			// only use portrait for phones for now
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		}

		mSettings = PreferenceManager.getDefaultSharedPreferences(this);
		mSettings.registerOnSharedPreferenceChangeListener(new OnSharedPreferenceChangeListener() {
			@Override
			public void onSharedPreferenceChanged(
					SharedPreferences sharedPreferences, String key) {
				readUiPrefs();
			}
		});

		if (savedInstanceState == null || savedInstanceState.getInt("ControllerMode", MODE_ENGINE) == MODE_ENGINE) {
			if (!initEngineController()) {
				return;
			}
		} else {
			mGameController = BluetoothGameController.getLastInstance(this);
			mGameController.setGui(this);
			setBluetoothDiscoverable();
		}

		if (mGameController == null) {
			Toast.makeText(this, R.string.could_not_initialize_game_controller, Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		mGameController.setGui(this);

		if (mGameController.getGameTextListener() == null) {
			mGameController.setGameTextListener(new PGNScreenText(PreferenceManager.getDefaultSharedPreferences(this),
					new PGNOptions()));
		}

		if (!mGameController.hasGame()) {
			// restore controller from saved instance state if possible
			if (savedInstanceState != null
					&& savedInstanceState.containsKey("ControllerMode")
					&& savedInstanceState.containsKey("GameMode")
					&& savedInstanceState.containsKey("Status")) {
				GameMode gameMode = GameMode.values()[savedInstanceState.getInt("GameMode")];
				mGameController.restoreGame(gameMode, savedInstanceState.getByteArray("Status"));
			} else {
				if (savedInstanceState != null && !(mGameController instanceof BluetoothGameController)) {
					// indicate that an error occurred attempting to reload from the saved instance state
					Toast.makeText(this, R.string.game_could_not_be_restored, Toast.LENGTH_SHORT).show();
				}

				int gameMode = mSettings.getInt("GameMode2", -1);
				String dataStr = mSettings.getString("GameState2", null);
				if (dataStr != null && gameMode >= 0) {
					mGameController.restoreGame(GameMode.values()[gameMode], strToByteArr(dataStr));
				}
			}
		}

		initUi();

		if (mGameController != null && mGameController.isGameActive()) {
			clearCurrentPgnGame();
		}

		String currentPgnFile = getCurrentPgnFile();
		if (mGameController.getGameMode() == null && TextUtils.isEmpty(currentPgnFile)
				&& !(mGameController instanceof BluetoothGameController)) {
			createNewGame();
		} else if (!TextUtils.isEmpty(currentPgnFile)) {
			mGameController.setGameMode(GameMode.ANALYSIS);
		}
	}

	private boolean initEngineController() {
		try {
			mGameController = EngineController.getInstance();
			mGameController.setGui(this);
			return true;
		} catch (UnsatisfiedLinkError e) {
			// critical error, abort
			Toast.makeText(this, R.string.dk_this_platform_not_supported, Toast.LENGTH_LONG).show();
			finish();
			return false;
		}
	}

	private static byte[] strToByteArr(String str) {
		int nBytes = str.length() / 2;
		byte[] ret = new byte[nBytes];
		for (int i = 0; i < nBytes; i++) {
			int c1 = str.charAt(i * 2) - 'A';
			int c2 = str.charAt(i * 2 + 1) - 'A';
			ret[i] = (byte) (c1 * 16 + c2);
		}
		return ret;
	}

	private static String byteArrToString(byte[] data) {
		StringBuilder ret = new StringBuilder(32768);

		for (byte aData : data) {
			int b = aData;
			if (b < 0)
				b += 256;
			char c1 = (char) ('A' + (b / 16));
			char c2 = (char) ('A' + (b & 15));
			ret.append(c1);
			ret.append(c2);
		}

		return ret.toString();
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		if (mGameController == null) {
			return;
		}

		outState.putInt("ControllerMode", mGameController instanceof EngineController ? MODE_ENGINE
				: MODE_BLUETOOTH);

		if (mGameController.getGameMode() != null) {
			int gameModeOrdinal = mGameController.getGameMode().ordinal();
			outState.putInt("GameMode", gameModeOrdinal);

			if (mGameController.getGame() != null) {
				byte[] data = mGameController.getPersistableGameState();
				outState.putByteArray("Status", data);
			}
		}
	}

	/**
	 * Read UI preferences for the UI and update accordingly.
	 */
	private void readUiPrefs() {
		//oneTouchMoves = mSettings.getBoolean("oneTouchMoves", false);
		oneTouchMoves = false;

		mShowThinking = mSettings.getBoolean("showThinking", false);
		maxNumArrows = Integer.parseInt(mSettings.getString("thinkingArrows", "2"));
		mShowBookHints = mSettings.getBoolean("bookHints", false);

		soundEnabled = mSettings.getBoolean("soundEnabled", false);
	}

	/**
	 * Configure the UI.
	 */
	private void initUi() {
		// read preferences first
		readUiPrefs();

		setContentView(R.layout.main);

		mCoordinatorView = (CoordinatorLayout) findViewById(R.id.coordinator);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		mFab = (FloatingActionButton) findViewById(R.id.fab);
		mFab.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				createNewGame();
			}
		});

		if (mGameController != null && mGameController.getGame() == null) {
			mFab.show();
		} else {
			mFab.hide();
		}

		thinkingInfoView = (TextView) findViewById(R.id.thinking_info);

		mStatusView = (TextView) findViewById(R.id.status);
		moveListScrollView = (ScrollView) findViewById(R.id.move_list_scroll_view);
		moveListView = (TextView) findViewById(R.id.moveList);
		mStatusView.setFocusable(false);
		moveListScrollView.setFocusable(false);
		moveListView.setFocusable(false);

		mChessBoardView = (ChessBoardView) findViewById(R.id.chessboard);
		mChessBoardView.setFocusable(true);
		mChessBoardView.requestFocus();
		mChessBoardView.setClickable(true);
		mChessBoardView.oneTouchMoves = oneTouchMoves;
		mChessBoardView.setColors();

		mChessBoardView.setOnLongClickListener(new OnLongClickListener() {
			public boolean onLongClick(View v) {
				removeDialog(CLIPBOARD_DIALOG);
				showDialog(CLIPBOARD_DIALOG);
				return true;
			}
		});

		setBoardFlip();
		updateThinkingInfoDisplay();

		if (mGameController != null && mGameController.getGame() != null) {
			mChessBoardView.setPosition(mGameController.getGame().currPos());
		}


		mPreviousMoveButton = (ImageButton) findViewById(R.id.go_back_move_btn);
		mPreviousMoveButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mGameController == null || mGameController.isAnalysisQuickPause()) {
					return;
				}

				((EngineController) mGameController).undoMove();
			}
		});

		mNextMoveButton = (ImageButton) findViewById(R.id.go_forward_move_btn);
		mNextMoveButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mGameController == null || mGameController.isAnalysisQuickPause()) {
					return;
				}

				((EngineController) mGameController).redoMove();
			}
		});
	}

	private void invalidateUi() {
		ActivityCompat.invalidateOptionsMenu(this);
		updateUndoRedoMoveArrowVisibility();

		if (mGameController != null && mGameController instanceof EngineController) {
			((EngineController) mGameController).checkGameStateAsync(new EngineController.AsyncGameStateCheckListener() {
				@Override
				public void onGameStateReceived(EngineController controller, Game.Status status) {
					if (status != Game.Status.ALIVE) {
						mFab.show();
						canResign = false;
					} else {
						mFab.hide();
						canResign = true;
					}

					ActivityCompat.invalidateOptionsMenu(DarkKnightActivity.this);
				}
			});
		} else if (mGameController != null) {
			if (mGameController.getGame() == null
					|| mGameController.getGame().getGameStatus() != Game.Status.ALIVE) {
				mFab.show();
				canResign = false;
			} else {
				mFab.hide();
				canResign = true;
			}
		}

		boolean chessboardEnabled;
		if (mGameController != null && mGameController.hasGame()) {
			updateMoveListDisplay();
			updateThinkingInfoDisplay();

			mChessBoardView.setPosition(mGameController.getGame().currPos());
			mChessBoardView.setSelectionFromMove(mGameController.getGame().getLastMove());

			chessboardEnabled = mGameController.isGameActive() || mGameController.isAnalyzing();
			setBoardFlip();
		} else {
			moveListView.setText(null);
			mChessBoardView.clearMoveHints();
			mChessBoardView.clearSelection();
			mChessBoardView.setPosition(null);

			chessboardEnabled = false;
		}

		if (chessboardEnabled) {
			enableChessBoard();
		} else {
			disableChessBoard();
		}

		mStatusView.setText(mGameController != null ? mGameController.getStatusText() : null);
	}


	private void enableChessBoard() {
		mChessBoardView.setEnabled(true);
		mChessBoardView.setEventListener(new ChessBoardEventListener(mGameController));
	}

	private void disableChessBoard() {
		mChessBoardView.setEnabled(false);
		mChessBoardView.setEventListener(null);
	}

	@Override
	protected void onStart() {
		super.onStart();

		invalidateUi();
		mGameController.setGui(this);

		if (mGameController instanceof EngineController) {
			((EngineController) mGameController).setMaxDepth(
					Integer.valueOf(mSettings.getString("difficultyDepth2", "3")));

			if (mGameController.getGameMode() == GameMode.ANALYSIS && !mGameController.isAnalyzing()) {
				((EngineController) mGameController).switchToAnalysisMode();
			}
		} else if (mGameController instanceof BluetoothGameController) {
			if (((BluetoothGameController) mGameController).isListening()) {
				onWaitingForOpponent();
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		lastVisibleMillis = 0;

		if (getIntent().getExtras() != null && getIntent().getExtras().containsKey("pgnData")) {
			// load the PGN data
			String pgnData = getIntent().getExtras().getString("pgnData");
			loadPGN(pgnData);
		}

		if (mGameController.hasGame() && !mGameController.getGameMode().analysisMode()) {
			mGameController.resumeGame();
		}

		updateNotification();
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (mGameController != null && mGameController.getGameMode() != null
				&& mGameController.getGame() != null) {
			if (!isChangingConfigurations()) {
				mGameController.pauseGame();
			}

			byte[] data = mGameController.getPersistableGameState();
			Editor editor = mSettings.edit();
			String dataStr = byteArrToString(data);
			editor.putInt("GameMode2", mGameController.getGameMode().ordinal());
			editor.putString("GameState2", dataStr);
			editor.apply();
		}

		lastVisibleMillis = System.currentTimeMillis();
		updateNotification();
	}

//	@Override
//	protected void onStop() {
//		super.onStop();
//
//		if (mGameController != null) {
//			mGameController.setGui(null);
//		}
//	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		if (isFinishing()) {
			if (mGameController != null) {
				mGameController.stopGame(false);
			}

			setNotification(false);
		}

		if (mGameController != null) {
			mGameController.setGui(null);
			mGameController = null;
		}

		mChessBoardView = null;
		mStatusView = null;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.options_menu, menu);

		menu.removeItem(R.id.item_goto_move);           // don't allow go-to move for now

		return true;
	}

	public boolean onPrepareOptionsMenu(Menu menu) {
		final MenuItem editBoardMenuItem = menu.findItem(R.id.item_editboard);
		final MenuItem forceMoveMenuItem = menu.findItem(R.id.item_force_move);
		final MenuItem offerDrawMenuItem = menu.findItem(R.id.item_draw);
		final MenuItem resignMenuItem = menu.findItem(R.id.item_resign);
		final MenuItem stopGameMenuItem = menu.findItem(R.id.item_stop_game);
		final MenuItem startAnalysisMenuItem = menu.findItem(R.id.item_start_analysis);
		final MenuItem stopAnalysisMenuItem = menu.findItem(R.id.item_stop_analysis);
		final MenuItem flipBoardMenuItem = menu.findItem(R.id.item_flip_board);
		final MenuItem recreateActivityMenuItem = menu.findItem(R.id.recreate_activity);
		final MenuItem loadPgnMenuItem = menu.findItem(R.id.item_load_pgn_file);

		recreateActivityMenuItem.setVisible(BuildConfig.DEBUG);

		final boolean hasGame = mGameController != null && mGameController.getGame() != null;
		final boolean isUsingBluetooth = mGameController instanceof BluetoothGameController;
		final boolean gameIsAlive = hasGame
				&& mGameController.getGame().getGameStatus() == Game.Status.ALIVE;
		final boolean isPlayerTurn = hasGame && mGameController.isPlayerTurn();

		editBoardMenuItem.setEnabled(hasGame && !isUsingBluetooth);

		forceMoveMenuItem.setEnabled(hasGame && !isUsingBluetooth
				&& mGameController.isOpponentThinking());
		offerDrawMenuItem.setEnabled(isPlayerTurn);

		final boolean hasBluetooth = BluetoothAdapter.getDefaultAdapter() != null;

		final boolean canAnalyze = hasGame && !mGameController.isAnalyzing();
		startAnalysisMenuItem.setVisible(canAnalyze && !isUsingBluetooth);
		startAnalysisMenuItem.setEnabled(!mGameController.isOpponentThinking());
		stopAnalysisMenuItem.setVisible(hasGame && !canAnalyze && !isUsingBluetooth);
		flipBoardMenuItem.setVisible(hasGame && !canAnalyze && !isUsingBluetooth);

		resignMenuItem.setVisible(gameIsAlive && canResign && canAnalyze);
		resignMenuItem.setEnabled(isPlayerTurn);

		stopGameMenuItem.setEnabled(gameIsAlive && canAnalyze);

		// stop game button is actually just a "feel good" resign button, although it can also
		// be run while the computer is playing
		stopGameMenuItem.setVisible(gameIsAlive && !(mGameController instanceof BluetoothGameController));
		loadPgnMenuItem.setVisible(mGameController instanceof EngineController);

		final MenuItem bluetoothSubmenu = menu.findItem(R.id.bluetooth_submenu);
		bluetoothSubmenu.setVisible(hasBluetooth);
		bluetoothSubmenu.setEnabled(mGameController == null || (!mGameController.isAnalyzing()
				&& !mGameController.isOpponentThinking()));
		if (hasBluetooth) {
			final boolean isConnected = isUsingBluetooth && gameIsAlive && mGameController.isGameActive();
			bluetoothSubmenu.setIcon(isConnected ?
					ContextCompat.getDrawable(this, R.drawable.ic_bluetooth_connected_white_24dp) :
					ContextCompat.getDrawable(this, R.drawable.ic_bluetooth_white_24dp));

			if (isUsingBluetooth && !isConnected) {
				// if not connected to Bluetooth, resigning is definitely not available
				resignMenuItem.setVisible(false);
			}

			final MenuItem startBluetoothGame = menu.findItem(R.id.bluetooth_create);
			startBluetoothGame.setEnabled(isUsingBluetooth && !isConnected);

			final MenuItem bluetoothDiscoverableMenuItem = menu.findItem(R.id.bluetooth_set_discoverable);

			final boolean isListeningOnBluetooth = isUsingBluetooth
					&& ((BluetoothGameController) mGameController).isListening();
			bluetoothDiscoverableMenuItem.setChecked(isUsingBluetooth && isBluetoothDiscoverable()
					&& (isListeningOnBluetooth || isConnected));
			bluetoothDiscoverableMenuItem.setEnabled(!isConnected);

			final MenuItem disconnectMenuItem = menu.findItem(R.id.bluetooth_disconnect);
			disconnectMenuItem.setVisible(isConnected);
		}

		return true;
	}

	private boolean isBluetoothDiscoverable() {
		return BluetoothAdapter.getDefaultAdapter().getScanMode()
				== BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
	}

	public void setBluetoothDiscoverable() {
		if (isBluetoothDiscoverable()) {
			// already discoverable
			return;
		}

		Intent discoverableIntent = new Intent(
				BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
		discoverableIntent.putExtra(
				BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
		startActivityForResult(discoverableIntent, REQUEST_ENABLE_BT);
	}

	private boolean createNewGame() {
		clearCurrentPgnGame();
		if (mGameController instanceof BluetoothGameController) {
			Intent serverIntent = new Intent(this, DeviceListActivity.class);
			startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
			return true;
		} else {
			if (mGameController.hasGame()) {
				mGameController.stopGame(false);
			}

			mGameController.setGameMode(getNextColor());
			mGameController.startGame();
		}

		invalidateUi();
		return true;
	}

	private void destroyGame() {
		if (mGameController == null) {
			return;
		}

		mGameController.setGui(null);
		mGameController.stopGame(false);
		mGameController = null;

		resetChessBoardView();
		moveListView.setText(null);
	}

	private void resetChessBoardView() {
		if (mChessBoardView == null) {
			return;
		}

		mChessBoardView.setPosition(Position.START_POSITION);
		mChessBoardView.clearMoveHints();
		mChessBoardView.clearSelection();
		mChessBoardView.setEventListener(null);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
	                                       @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);

		if (requestCode == PERMISSIONS_REQUEST_READ_STORAGE) {
			if (grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				showDialog(SELECT_PGN_FILE_DIALOG);
			} else {
				Toast.makeText(this, R.string.dark_knight_does_not_have_external_storage_permission,
						Toast.LENGTH_SHORT).show();
			}
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.item_start_analysis:
				((EngineController) mGameController).switchToAnalysisMode();
				boardFlippedForAnalysis = getBoardFlipFromCurrentPosition();
				invalidateUi();
				return true;
			case R.id.item_stop_analysis:
				((EngineController) mGameController).switchToComputerPlayMode();
				mChessBoardView.setMoveHints(null);
				invalidateUi();
				return true;
			case R.id.item_flip_board:
				boardFlippedForAnalysis = !boardFlippedForAnalysis;
				setBoardFlip();
				return true;
			case R.id.item_editboard: {
				Intent i = new Intent(DarkKnightActivity.this, EditBoardActivity.class);
				i.setAction(mGameController.getGame().currPos().getFEN());
				startActivityForResult(i, RESULT_EDITBOARD);
				return true;
			}
			case R.id.item_settings: {
				Intent i = new Intent(DarkKnightActivity.this, SettingsActivity.class);
				startActivityForResult(i, RESULT_SETTINGS);
				return true;
			}
			case R.id.item_goto_move:
				showDialog(SELECT_MOVE_DIALOG);
				return true;
			case R.id.item_force_move:
				((EngineController) mGameController).stopSearch();
				return true;
			case R.id.item_draw:
				if (mGameController.isPlayerTurn()) {
					if (!mGameController.claimDrawIfPossible()) {
						Toast.makeText(getApplicationContext(), R.string.offer_draw,
								Toast.LENGTH_SHORT).show();
					}
				}
				return true;
			case R.id.item_resign:
				if (mGameController.isPlayerTurn()) {
					removeDialog(CONFIRM_RESIGN_DIALOG);
					showDialog(CONFIRM_RESIGN_DIALOG);
				}
				return true;
			case R.id.item_stop_game:
				if (mGameController != null) {
					mGameController.stopGame();
				}
				return true;
			case R.id.item_load_pgn_file:
				removeDialog(SELECT_PGN_FILE_DIALOG);

				if (ContextCompat.checkSelfPermission(this,
						Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
					ActivityCompat.requestPermissions(this,
							new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
							PERMISSIONS_REQUEST_READ_STORAGE);
				} else {
					showDialog(SELECT_PGN_FILE_DIALOG);
				}
				return true;
			case R.id.bluetooth_create:
				Intent serverIntent = new Intent(this, DeviceListActivity.class);
				startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
				return true;
			case R.id.bluetooth_set_discoverable:
				if (!item.isChecked()) {
					if (isBluetoothDiscoverable()) {
						switchFromEngineToBluetooth();
					} else {
						setBluetoothDiscoverable();
					}
				} else {
					switchFromBluetoothToEngine();
				}

				return true;
			case R.id.bluetooth_disconnect:
				mGameController.stopGame();
				break;
			case R.id.item_about:
				showDialog(ABOUT_DIALOG);
				return true;
			case R.id.recreate_activity:
				recreate();
				return true;
		}

		return false;
	}

	private void switchFromEngineToBluetooth() {
		if (BluetoothAdapter.getDefaultAdapter() == null || !BluetoothAdapter.getDefaultAdapter().isEnabled()) {
			Snackbar.make(mCoordinatorView, R.string.enable_bluetooth_to_play, Snackbar.LENGTH_LONG).show();
			return;
		}

		destroyGame();

		final boolean isSwitching = mGameController instanceof EngineController;

		final BluetoothGameController bGameCtrl
				= new BluetoothGameController(getApplicationContext());
		bGameCtrl.setGui(this);
		bGameCtrl.setGameTextListener(new PGNScreenText(PreferenceManager.getDefaultSharedPreferences(this),
				new PGNOptions()));
		mGameController = bGameCtrl;

		setBluetoothDiscoverable();
		invalidateUi();

		if (isSwitching) {
			Toast.makeText(this, R.string.switched_to_bluetooth_play, Toast.LENGTH_LONG).show();
		}
	}

	private void switchFromBluetoothToEngine() {
		if (!(mGameController instanceof BluetoothGameController)) {
			return;
		}

		destroyGame();

		initEngineController();
		createNewGame();
		Toast.makeText(this, R.string.switched_to_playing_against_the_computer, Toast.LENGTH_LONG).show();
	}

	private GameMode getNextColor() {
		final int colorPreference = Integer.valueOf(mSettings.getString("colorPreference", "0"));

		switch (colorPreference) {
			case 1:
				return GameMode.PLAYER_WHITE;
			case 2:
				return GameMode.PLAYER_BLACK;
			default:
				return new Random().nextBoolean() ? GameMode.PLAYER_WHITE
						: GameMode.PLAYER_BLACK;
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case RESULT_SETTINGS:
				readUiPrefs();
				break;
			case RESULT_EDITBOARD:
				if (resultCode == RESULT_OK) {
					try {
						String fen = data.getAction();
						((EngineController) mGameController).setFENOrPGN(fen);
					} catch (ChessParseError e) {
						e.printStackTrace();
					}
				}
				break;
			case RESULT_LOAD_PGN:
				if (resultCode == RESULT_OK) {
					loadPGN(data.getAction());
				}
				break;
			case REQUEST_CONNECT_DEVICE:
				// when DeviceListActivity returns with a device to connect
				if (resultCode == Activity.RESULT_OK) {
					destroyGame();

					final BluetoothGameController bGameCtrl
							= new BluetoothGameController(getApplicationContext());
					bGameCtrl.setGameMode(getNextColor());
					bGameCtrl.setGui(this);
					bGameCtrl.setGameTextListener(new PGNScreenText(PreferenceManager.getDefaultSharedPreferences(this),
							new PGNOptions()));
					mGameController = bGameCtrl;

					// get the device MAC address
					String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
					bGameCtrl.connectToDevice(address);
				}
				break;
			case REQUEST_ENABLE_BT:
				// when the request to enable Bluetooth returns
				if (resultCode > 0) {
					switchFromEngineToBluetooth();
				} else {
					// Bluetooth not enabled or an error occurred
					Snackbar.make(mCoordinatorView, R.string.bt_not_enabled_leaving,
							Snackbar.LENGTH_LONG).show();
				}

				break;
		}

		ActivityCompat.invalidateOptionsMenu(this);
	}

	private void loadPGN(String pgn) {
		if (!(mGameController instanceof EngineController)) {
			throw new IllegalStateException("Must use engine controller for loading PGNs");
		}

		try {
			mGameController.stopGame(false);
			mGameController.setGameMode(GameMode.ANALYSIS);
			mGameController.setGameTextListener(new PGNScreenText(PreferenceManager.getDefaultSharedPreferences(this),
					new PGNOptions()));
			((EngineController) mGameController).startNewGameFromFENorPGN(pgn);
			invalidateUi();
		} catch (ChessParseError e) {
			Toast.makeText(getApplicationContext(), e.getMessage(),
					Toast.LENGTH_SHORT).show();
		}
	}

	@SuppressWarnings("ConstantConditions")
	private boolean getBoardFlipFromCurrentPosition() {
		return !mGameController.getGame().currPos().whiteMove;
	}

	private void setBoardFlip() {
		final GameMode gameMode = mGameController.getGameMode();

		boolean boardFlipped;
		if (gameMode == GameMode.ANALYSIS) {
			boardFlipped = boardFlippedForAnalysis;
		} else {
			boardFlipped = gameMode == GameMode.PLAYER_BLACK
					|| (gameMode == GameMode.TWO_PLAYERS && mGameController.getGame() != null
					&& getBoardFlipFromCurrentPosition());
		}

		mChessBoardView.setFlipped(boardFlipped);
	}

	@Override
	public void setStatusString(String str) {
		mStatusView.setText(str);
	}

	private PGNScreenText getPGNTokenReceiver() {
		return (PGNScreenText) mGameController.getGameTextListener();
	}

	private String getStringForGameMode(GameMode gameMode) {
		switch (gameMode) {
			case PLAYER_WHITE:
				return getString(R.string.game_mode_player_white);
			case PLAYER_BLACK:
				return getString(R.string.game_mode_player_black);
			case TWO_COMPUTERS:
				return getString(R.string.game_mode_two_computers);
			case TWO_PLAYERS:
				return getString(R.string.game_mode_two_players);
			case ANALYSIS:
				return getString(R.string.game_mode_analysis);
			default:
				return getString(R.string.game_mode_unknown);
		}
	}

	@Override
	public void onGameStarted() {
		if (mGameController.getGame() == null) {
			throw new IllegalStateException("An internal error occurred, game was null");
		}

		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "Game started at position " + mGameController.getGame().currPos().getFEN());
		}

		invalidateUi();

		if (mGameController.getGameMode() != GameMode.ANALYSIS) {
			Snackbar.make(mCoordinatorView, getString(R.string.new_game_started_as_kind,
					getStringForGameMode(mGameController.getGameMode())),
					Snackbar.LENGTH_SHORT).show();
		}

		mGameController.resumeGame();
	}


	@Override
	public void onGameRestored() {
		resetChessBoardView();
		setBoardFlip();
		resetChessBoardView();

		if (mGameController.isResumed()) {
			return;
		}

		mGameController.resumeGame();
	}

	@Override
	public void onGameResumed() {
		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "Game resumed at position " + mGameController.getGame().currPos().getFEN());
		}

		invalidateUi();
	}

	@Override
	public void onGamePaused() {
		disableChessBoard();
	}

	@Override
	public void showMessage(CharSequence message, int duration) {
		mCurrentSnackbar = Snackbar.make(mCoordinatorView, message, duration);
		mCurrentSnackbar.show();
	}

	@Override
	public void showToast(CharSequence message, int duration) {
		Toast.makeText(this, message, duration).show();
	}

	@Override
	public void dismissMessage() {
		mCurrentSnackbar.dismiss();
	}

	@Override
	public void onAnalysisInterrupted() {
		Toast.makeText(this, R.string.analysis_was_interrupted, Toast.LENGTH_SHORT).show();
		invalidateUi();
	}

	@Override
	public void onGameStopped() {
		if (mStatusView != null) {
			mStatusView.setText(null);
		}

		invalidateUi();
	}

	@Override
	public void onThinkingInfoChanged(final ThinkingInfo thinkingInfo) {
		currentThinkingInfo = thinkingInfo;

		if (thinkingInfo != null) {
			lastComputationMillis = System.currentTimeMillis();
		} else {
			lastComputationMillis = 0;
		}

		updateThinkingInfoDisplay();
		updateNotification();
	}

	private void updateThinkingInfoDisplay() {
		if (mGameController == null || !mGameController.hasGame()) {
			return;
		}

		if (currentThinkingInfo == null) {
			mChessBoardView.clearMoveHints();
			thinkingInfoView.setText(null);
			return;
		}

		final GameMode gameMode = mGameController.getGameMode();

		boolean thinkingEmpty = true;
		{
			String s = "";
			if (mShowThinking || gameMode.analysisMode()) {
				s = currentThinkingInfo.pvStr;
			}

			thinkingInfoView.setText(s, TextView.BufferType.SPANNABLE);

			if (!TextUtils.isEmpty(s)) {
				Log.d(getClass().getName(), "Thinking info: " + s);
			}

			if (s.length() > 0) {
				thinkingEmpty = false;
			}
		}

		if (mShowBookHints && (currentThinkingInfo.bookInfo.length() > 0)) {
			String s = "";
			if (!thinkingEmpty)
				s += "<br>";
			s += "<b>Book:</b>" + currentThinkingInfo.bookInfo;
			thinkingInfoView.append(Html.fromHtml(s));
			thinkingEmpty = false;
		}

		if (variantStr.indexOf(' ') >= 0) {
			String s = "";
			if (!thinkingEmpty)
				s += "<br>";
			s += "<b>Var:</b> " + variantStr;
			thinkingInfoView.append(Html.fromHtml(s));
		}

		List<Move> hints = null;

		if (mShowThinking || gameMode.analysisMode()) {
			hints = currentThinkingInfo.pvMoves;
		}

		if ((hints == null) && mShowBookHints) {
			hints = currentThinkingInfo.bookMoves;
		}

		if ((variantMoves != null) && variantMoves.size() > 1) {
			hints = variantMoves;
		}

		if ((hints != null) && (hints.size() > maxNumArrows)) {
			hints = hints.subList(0, maxNumArrows);
		}

		if (mGameController.getGameMode() == GameMode.ANALYSIS) {
			// for analysis, show move hints (arrows on the board)
			mChessBoardView.setMoveHints(hints);
		}
	}

	private void updateMoveListDisplay() {
		moveListView.setText(getPGNTokenReceiver().getSpannableData());
	}


	static final int PROMOTE_DIALOG = 0;
	static final int CLIPBOARD_DIALOG = 1;
	static final int ABOUT_DIALOG = 2;
	static final int SELECT_MOVE_DIALOG = 3;
	static final int SELECT_BOOK_DIALOG = 4;
	static final int SELECT_PGN_FILE_DIALOG = 5;
	static final int SET_COLOR_THEME_DIALOG = 6;
	static final int CONFIRM_RESIGN_DIALOG = 7;

	static final int PERMISSIONS_REQUEST_READ_STORAGE = 0x44;

	@Nullable
	private String getCurrentPgnFile() {
		return mSettings.getString("currentPGNFile2", null);
	}

	@Override
	protected AlertDialog onCreateDialog(final int id) {
		switch (id) {
			case PROMOTE_DIALOG: {
				final CharSequence[] items = {getString(R.string.queen),
						getString(R.string.rook), getString(R.string.bishop),
						getString(R.string.knight)};
				AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
				builder.setTitle(R.string.promote_pawn_to);
				builder.setItems(items, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int item) {
						mGameController.setPromotionChoice(PromotionPiece.values()[item]);
					}
				});

				return builder.create();
			}
			case CLIPBOARD_DIALOG: {
				final int COPY_GAME = 0;
				final int COPY_POSITION = 1;
				final int PASTE = 2;
				final int LOAD_GAME = 3;
				final int REMOVE_VARIATION = 4;

				List<CharSequence> lst = new ArrayList<>();
				List<Integer> actions = new ArrayList<>();
				lst.add(getString(R.string.copy_game));
				actions.add(COPY_GAME);
				lst.add(getString(R.string.copy_position));
				actions.add(COPY_POSITION);
				lst.add(getString(R.string.paste));
				actions.add(PASTE);
				lst.add(getString(R.string.load_game));
				actions.add(LOAD_GAME);
				if (mGameController.isPlayerTurn() && (((EngineController) mGameController).numVariations() > 1)) {
					lst.add(getString(R.string.remove_variation));
					actions.add(REMOVE_VARIATION);
				}
				final List<Integer> finalActions = actions;
				AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
				builder.setTitle(R.string.tools_menu);
				builder.setItems(lst.toArray(new CharSequence[4]),
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int item) {
								switch (finalActions.get(item)) {
									case COPY_GAME: {
										String pgn = mGameController.getPGN();
										ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
										clipboard.setText(pgn);
										break;
									}
									case COPY_POSITION: {
										String fen = mGameController.getGame().currPos().getFEN() + "\n";
										ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
										clipboard.setText(fen);
										break;
									}
									case PASTE: {
										ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
										if (clipboard.hasText()) {
											String fenPgn = clipboard.getText()
													.toString();
											try {
												((EngineController) mGameController).setFENOrPGN(fenPgn);
											} catch (ChessParseError e) {
												Toast.makeText(getApplicationContext(),
														e.getMessage(),
														Toast.LENGTH_SHORT).show();
											}
										}
										break;
									}
									case LOAD_GAME:
										removeDialog(SELECT_PGN_FILE_DIALOG);
										showDialog(SELECT_PGN_FILE_DIALOG);
										break;
//									case REMOVE_VARIATION:
//										((EngineController) mGameController).removeVariation();
//										break;
								}
							}
						});
				return builder.create();
			}
			case ABOUT_DIALOG: {
				AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
				builder.setTitle(R.string.app_name).setMessage(R.string.about_info);
				return builder.create();
			}
			case SELECT_MOVE_DIALOG: {
//				final Dialog dialog = new Dialog(this);
//				dialog.setContentView(R.layout.select_move_number);
//				dialog.setTitle(R.string.goto_move);
//				final EditText moveNrView = (EditText) dialog
//						.findViewById(R.id.selmove_number);
//				Button ok = (Button) dialog.findViewById(R.id.selmove_ok);
//				Button cancel = (Button) dialog.findViewById(R.id.selmove_cancel);
//				moveNrView.setText("1");
//				final Runnable gotoMove = new Runnable() {
//					public void run() {
//						try {
//							int moveNr = Integer.parseInt(moveNrView.getText()
//									.toString());
//							((EngineController) mGameController).goToMove(moveNr);
//							dialog.cancel();
//						} catch (NumberFormatException nfe) {
//							Toast.makeText(getApplicationContext(),
//									R.string.invalid_number_format,
//									Toast.LENGTH_SHORT).show();
//						}
//					}
//				};
//				moveNrView.setOnKeyListener(new OnKeyListener() {
//					public boolean onKey(View v, int keyCode, KeyEvent event) {
//						if ((event.getAction() == KeyEvent.ACTION_DOWN)
//								&& (keyCode == KeyEvent.KEYCODE_ENTER)) {
//							gotoMove.run();
//							return true;
//						}
//						return false;
//					}
//				});
//				ok.setOnClickListener(new OnClickListener() {
//					public void onClick(View v) {
//						gotoMove.run();
//					}
//				});
//				cancel.setOnClickListener(new OnClickListener() {
//					public void onClick(View v) {
//						dialog.cancel();
//					}
//				});
//				return dialog;
				break;
			}
			case SELECT_BOOK_DIALOG: {
				String[] fileNames = findFilesInDirectory(BOOK_DIR);
				final int numFiles = fileNames.length;
				CharSequence[] items = new CharSequence[numFiles + 1];
				System.arraycopy(fileNames, 0, items, 0, numFiles);
				items[numFiles] = getString(R.string.internal_book);
				final CharSequence[] finalItems = items;
				int defaultItem = numFiles;
				for (int i = 0; i < numFiles; i++) {
					if (((EngineController) mGameController).getBookFileName().equals(items[i])) {
						defaultItem = i;
						break;
					}
				}
				AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
				builder.setTitle(R.string.select_opening_book_file);
				builder.setSingleChoiceItems(items, defaultItem,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int item) {
								Editor editor = mSettings.edit();
								String bookFile = "";
								if (item < numFiles)
									bookFile = finalItems[item].toString();
								editor.putString("bookFile", bookFile);
								editor.apply();
								dialog.dismiss();
							}
						});
				return builder.create();
			}
			case SELECT_PGN_FILE_DIALOG: {
				final String[] fileNames = findFilesInDirectory(PGN_DIR);
				final int numFiles = fileNames.length;
				if (numFiles == 0) {
					AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
					builder.setTitle(R.string.app_name).setMessage(
							R.string.no_pgn_files);
					return builder.create();
				}

				int defaultItem = -1;
				String currentPGNFile = mSettings.getString("currentPGNFile2", "");
				for (int i = 0; i < numFiles; i++) {
					if (currentPGNFile.equals(fileNames[i])) {
						defaultItem = i;
						break;
					}
				}

				AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
				builder.setTitle(R.string.select_pgn_file);
				builder.setSingleChoiceItems(fileNames, defaultItem,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int item) {
								Editor editor = mSettings.edit();
								String pgnFile = fileNames[item];
								editor.putString("currentPGNFile2", pgnFile);
								editor.apply();
								String sep = File.separator;
								String pathName = PGN_DIR + sep + pgnFile;
								Intent i = new Intent(DarkKnightActivity.this,
										LoadPGNActivity.class);
								i.setAction(pathName);
								startActivityForResult(i, RESULT_LOAD_PGN);
								dialog.dismiss();
							}
						});
				return builder.create();
			}
			case CONFIRM_RESIGN_DIALOG: {
				AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert);
				builder.setMessage(R.string.confirm_resign)
						.setPositiveButton(android.R.string.yes,
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog,
									                    int id) {
										if (mGameController.isPlayerTurn()) {
											mGameController.resignGame();
										}
									}
								})
						.setNegativeButton(android.R.string.no,
								new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface dialog,
									                    int id) {
										dialog.cancel();
									}
								});
				return builder.create();
			}
		}
		return null;
	}

	@SuppressLint("CommitPrefEdits")
	private void clearCurrentPgnGame() {
		mSettings.edit().remove("currentPGNFile2").commit();
	}

	private static String[] findFilesInDirectory(String dirName) {
		File dir = new File(dirName);
		File[] files = dir.listFiles(new FileFilter() {
			public boolean accept(File pathname) {
				return pathname.isFile();
			}
		});
		if (files == null)
			files = new File[0];
		final int numFiles = files.length;
		String[] fileNames = new String[numFiles];
		for (int i = 0; i < files.length; i++)
			fileNames[i] = files[i].getName();
		Arrays.sort(fileNames, String.CASE_INSENSITIVE_ORDER);
		return fileNames;
	}

	@Override
	public void requestPromotePiece() {
		showDialog(PROMOTE_DIALOG);
	}

	@Override
	public void onInvalidMoveRejected(Move m) {
		mChessBoardView.clearSelection();
		String msg = String.format("Invalid move %s-%s",
				TextIO.squareToString(m.from), TextIO.squareToString(m.to));
		Snackbar.make(mCoordinatorView, msg, Snackbar.LENGTH_LONG).show();
	}

	private void updateUndoRedoMoveArrowVisibility() {
		boolean canUndoMove;
		boolean canRedoMove;

		if (mGameController == null || mGameController instanceof BluetoothGameController) {
			canUndoMove = false;
			canRedoMove = false;
		} else {
			canUndoMove = mGameController.canUndoMove();
			canRedoMove = mGameController.canRedoMove();
		}

		mPreviousMoveButton.setVisibility(canUndoMove ? View.VISIBLE : View.INVISIBLE);
		mNextMoveButton.setVisibility(canRedoMove ? View.VISIBLE : View.INVISIBLE);
	}

	@Override
	public void onOpponentBeganThinking() {
		disableChessBoard();
		ActivityCompat.invalidateOptionsMenu(this);
	}

	@Override
	public void onOpponentStoppedThinking() {
		enableChessBoard();
		ActivityCompat.invalidateOptionsMenu(this);
	}

	@Override
	public void onOpponentDisconnected(@Nullable String opponentName) {
		String error;
		if (opponentName == null) {
			error = getString(R.string.bluetooth_connection_to_device_lost_generic);
		} else {
			error = getString(R.string.bluetooth_connection_to_device_lost, opponentName);
		}

		Snackbar.make(mCoordinatorView, error, Snackbar.LENGTH_INDEFINITE).show();
		mGameController.pauseGame();
		canResign = false;

		mFab.show();
		ActivityCompat.invalidateOptionsMenu(this);
	}

	@Override
	public void onPositionChanged(Position newPosition, String variantInfo,
	                              List<Move> variantMoves) {
		Log.i(getClass().getSimpleName(), "Position changed to " + newPosition.getFEN());

		variantStr = variantInfo;
		this.variantMoves = variantMoves;
		mChessBoardView.setPosition(newPosition);
		setBoardFlip();
		updateThinkingInfoDisplay();
		updateMoveListDisplay();
		invalidateUi();
	}

	@SuppressWarnings("ConstantConditions")
	@Override
	public void onMoveMade(Move m) {
		if (BuildConfig.DEBUG) {
			Log.d(getClass().getSimpleName(), "Move made: " + m);
		}

		mChessBoardView.setSelectionFromMove(m);
		mChessBoardView.setPosition(mGameController.getGame().currPos());

		updateMoveListDisplay();

		if (getPGNTokenReceiver().atEnd()) {
			moveListScrollView.fullScroll(ScrollView.FOCUS_DOWN);
		}

		if (mGameController.getGameMode() == GameMode.TWO_PLAYERS) {
			setBoardFlip();
		}

		updateUndoRedoMoveArrowVisibility();

		if (!soundEnabled) {
			return;
		}

		if (moveSound != null) {
			moveSound.release();
		}

		moveSound = MediaPlayer.create(this, R.raw.movesound);
		moveSound.start();
	}

	@Override
	public void onMoveUnmade(Move m) {
		invalidateUi();
	}

	@Override
	public void onMoveRemade(Move m) {
		invalidateUi();
	}

	private String getStringFromGameStatus(Game.Status status) {
		switch (status) {
			case WHITE_MATE:
				return getString(R.string.game_result_white_mate);
			case BLACK_MATE:
				return getString(R.string.game_result_black_mate);
			case WHITE_STALEMATE:
				return getString(R.string.game_result_white_stalmate);
			case BLACK_STALEMATE:
				return getString(R.string.game_result_black_stalmate);
			case DRAW_REP:
				return getString(R.string.game_result_draw_rep);
			case DRAW_50:
				return getString(R.string.game_result_draw_50);
			case DRAW_NO_MATE:
				return getString(R.string.game_result_draw_no_mate);
			case DRAW_AGREE:
				return getString(R.string.game_result_draw_agree);
			case RESIGN_WHITE:
				return getString(R.string.game_result_resign_white);
			case RESIGN_BLACK:
				return getString(R.string.game_result_resign_black);
			default:
				return getString(R.string.game_result_unknown);
		}
	}

	@Override
	public void onGameOver(Game.Status endState) {
		Snackbar.make(mCoordinatorView, getString(R.string.game_over, getStringFromGameStatus(endState)),
				Snackbar.LENGTH_SHORT).show();

		mChessBoardView.clearSelection();
		mChessBoardView.setPosition(mGameController.getGame().currPos());

		moveListView.setText(getPGNTokenReceiver().getSpannableData());

		// show the FAB again
		mFab.show();
		canResign = false;

		ActivityCompat.invalidateOptionsMenu(this);
	}

	@Override
	public void onWaitingForOpponent() {
		mStatusView.setText(null);

		mCurrentSnackbar = Snackbar.make(mCoordinatorView,
				getString(R.string.waiting_for_a_bluetooth_opponent_to_connect),
				Snackbar.LENGTH_INDEFINITE);
		mCurrentSnackbar.setAction(R.string.stop_waiting_for_opponent, new OnClickListener() {
			@Override
			public void onClick(View v) {
				switchFromBluetoothToEngine();
			}
		});
		mCurrentSnackbar.show();

		invalidateUi();
	}

	@Override
	public void onConnectedToOpponent(CharSequence hint) {
		mCurrentSnackbar.dismiss();
		mStatusView.setText(hint);

		// hide the FAB since presumably the game is about to start
		mFab.hide();
	}

	@Override
	public void onOpponentOfferDraw(Move m) {
		onMoveMade(m);
		mCurrentSnackbar = Snackbar.make(mCoordinatorView, R.string.opponent_has_offered_a_draw,
				Snackbar.LENGTH_INDEFINITE);
		mCurrentSnackbar.setAction(R.string.accept_draw, new OnClickListener() {
			@Override
			public void onClick(View v) {
				mGameController.acceptDrawOffer();
			}
		});
		mCurrentSnackbar.setCallback(new Snackbar.Callback() {
			@Override
			public void onDismissed(Snackbar snackbar, @DismissEvent int event) {
				if (event == DISMISS_EVENT_SWIPE && mGameController != null) {
					mGameController.declineDrawOffer();
				}
			}
		});
		mCurrentSnackbar.show();
	}

	/**
	 * Decide if user should be warned about heavy CPU usage.
	 */
	private void updateNotification() {
		boolean warn = false;
		if (lastVisibleMillis != 0) { // GUI not visible
			warn = lastComputationMillis >= lastVisibleMillis + 5000;
		}

		setNotification(warn);
	}

	private boolean notificationActive = false;

	/**
	 * Set/clear the "heavy CPU usage" notification.
	 */
	private void setNotification(boolean show) {
		if (notificationActive == show) {
			return;
		}

		notificationActive = show;
		final int cpuUsage = 1;
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(ns);
		if (show) {
			CharSequence contentTitle = getString(R.string.background_processing);
			CharSequence contentText = getString(R.string.dark_knight_is_using_a_lot_of_cpu_power);
			Intent notificationIntent = new Intent(this, CPUWarningActivity.class);

			PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
					notificationIntent, 0);

			NotificationCompat.Builder mBuilder =
					new NotificationCompat.Builder(this)
							.setContentTitle(contentTitle)
							.setContentText(contentText)
							.setContentIntent(contentIntent);
			mNotificationManager.notify(cpuUsage, mBuilder.build());
		} else {
			mNotificationManager.cancel(cpuUsage);
		}
	}

	/*
	 * private final String timeToString(long time) { int secs = (int)
	 * Math.floor((time + 999) / 1000.0); boolean neg = false; if (secs < 0) {
	 * neg = true; secs = -secs; } int mins = secs / 60; secs -= mins * 60;
	 * StringBuilder ret = new StringBuilder(); if (neg) ret.append('-');
	 * ret.append(mins); ret.append(':'); if (secs < 10) ret.append('0');
	 * ret.append(secs); return ret.toString(); }
	 */

	/*
	 * private Handler handlerTimer = new Handler(); private Runnable r = new
	 * Runnable() { public void run() { mGameController.updateRemainingTime(); } };
	 */

	/* not yet optimized for Honeycomb */
	public void setRemainingTime(long wTime, long bTime, long nextUpdate) {
		// whiteClock.setText("White: " + timeToString(wTime));
		// blackClock.setText("Black: " + timeToString(bTime));
		// handlerTimer.removeCallbacks(r); //if (nextUpdate > 0) { //
		// handlerTimer.postDelayed(r, nextUpdate); //}
	}
}
