package org.empyrn.darkknight.gamelogic;

import android.annotation.SuppressLint;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.empyrn.darkknight.BuildConfig;
import org.empyrn.darkknight.DarkKnightActivity;
import org.empyrn.darkknight.GUIInterface;
import org.empyrn.darkknight.GameMode;
import org.empyrn.darkknight.engine.EnginePlayer;
import org.empyrn.darkknight.engine.ThinkingInfo;
import org.empyrn.darkknight.gamelogic.Game.Status;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The glue between the chess engine and the GUI.
 *
 * @author petero, nink
 */
public class EngineController extends AbstractGameController implements GameController {
	private String bookFileName = "";

	@Nullable
	private GameMode gameMode;

	@Nullable
	private	Game game;

	private ComputerMoveSelectionThread computerThread;
	private AnalysisThread analysisThread;

	private int timeControl;
	private int movesPerSession;
	private int timeIncrement;

	private int maxDepth;

	private static EngineController instance;

	// quick lock to enable when briefly restarting the analysis thread after playing a move,
	// to provide continuity for the isAnalyzing() method in multi-threaded environments
	private boolean isAnalysisQuickPause;

	private boolean isGameResumed = false;


	/**
	 * Get the {@link EngineController} singleton instance.
	 *
	 * @throws UnsatisfiedLinkError if the native platform isn't supported
	 */
	public static EngineController getInstance() throws UnsatisfiedLinkError {
		if (instance == null) {
			instance = new EngineController();
		}

		return instance;
	}

	private EngineController() {
		EnginePlayer.getInstance().setBookFileName(bookFileName);
	}

	@Nullable
	@Override
	public Game getGame() {
		return game;
	}

	abstract static class EngineControllerSearchListener implements SearchListener {
		private int currDepth = 0;
		private int currMoveNr = 0;
		private String currMove = "";
		private int currNodes = 0;
		private int currNps = 0;
		private int currTime = 0;

		private int pvDepth = 0;
		private int pvScore = 0;
		private boolean pvIsMate = false;
		private boolean pvUpperBound = false;
		private boolean pvLowerBound = false;
		private String bookInfo = "";
		private String pvStr = "";
		private List<Move> pvMoves = null;
		private List<Move> bookMoves = null;

		public final void clearSearchInfo() {
			pvDepth = 0;
			currDepth = 0;
			bookInfo = "";
			pvMoves = null;
			bookMoves = null;
			setSearchInfo();
		}

		EngineControllerSearchListener() {
			clearSearchInfo();
		}

		@SuppressLint("DefaultLocale")
		private void setSearchInfo() {
			StringBuilder buf = new StringBuilder();
			if (pvDepth > 0) {
				buf.append(String.format("[%d] ", pvDepth));
				if (pvUpperBound) {
					buf.append("<=");
				} else if (pvLowerBound) {
					buf.append(">=");
				}
				if (pvIsMate) {
					buf.append(String.format("m%d", pvScore));
				} else {
					buf.append(String.format("%.2f", pvScore / 100.0));
				}
				buf.append(pvStr);
				buf.append("\n");
			}

			if (currDepth > 0) {
				buf.append(String.format("d:%d %d:%s t:%.2f n:%d nps:%d",
						currDepth, currMoveNr, currMove, currTime / 1000.0,
						currNodes, currNps));
			}

			final String newPV = buf.toString();
			final String newBookInfo = bookInfo;

			ThinkingInfo newThinkingInfo = new ThinkingInfo(pvScore, newPV, newBookInfo, pvMoves, bookMoves);
			onThinkingInfoChanged(newThinkingInfo);
		}

		protected abstract void onThinkingInfoChanged(ThinkingInfo thinkingInfo);

		@Override
		public void notifyDepth(int depth) {
			currDepth = depth;
			setSearchInfo();
		}

		@Override
		public void notifyCurrMove(Position pos, Move m, int moveNr) {
			currMove = TextIO.moveToString(pos, m, false);
			currMoveNr = moveNr;
			setSearchInfo();
		}

		@Override
		public void notifyPV(Position pos, int depth, int score, int time,
		                     int nodes, int nps, boolean isMate, boolean upperBound,
		                     boolean lowerBound, ArrayList<Move> pv) {
			pvDepth = depth;
			pvScore = score;
			currTime = time;
			currNodes = nodes;
			currNps = nps;
			pvIsMate = isMate;
			pvUpperBound = upperBound;
			pvLowerBound = lowerBound;

			StringBuilder buf = new StringBuilder();
			Position tmpPos = new Position(pos);
			UndoInfo ui = new UndoInfo();
			for (Move m : pv) {
				buf.append(String.format(" %s",
						TextIO.moveToString(tmpPos, m, false)));
				tmpPos.makeMove(m, ui);
			}
			pvStr = buf.toString();
			pvMoves = pv;
			setSearchInfo();
		}

		@Override
		public void notifyStats(int nodes, int nps, int time) {
			currNodes = nodes;
			currNps = nps;
			currTime = time;
			setSearchInfo();
		}

		@Override
		public void notifyBookInfo(String bookInfo, List<Move> moveList) {
			this.bookInfo = bookInfo;
			bookMoves = moveList;
			setSearchInfo();
		}
	}

	public void setMaxDepth(int maxDepth) {
		this.maxDepth = maxDepth;
	}

	public String getBookFileName() {
		return this.bookFileName;
	}

//	public final void setBookFileName(String bookFileName) {
//		if (!this.bookFileName.equals(bookFileName)) {
//			this.bookFileName = bookFileName;
//			EnginePlayer.getInstance().setBookFileName(bookFileName);
//			if (analysisThread != null) {
//				stopAnalysis();
//				startAnalysis();
//			}
//
//			updateBookHints();
//		}
//	}

	private void updateBookHints() {
		if (gameMode == null || game == null) {
			return;
		}

		boolean analysis = (gameMode == GameMode.ANALYSIS);
		if (!analysis && isPlayerTurn()) {
			//ss = new SearchStatus();
			Pair<String, ArrayList<Move>> bi = EnginePlayer.getInstance()
					.getBookHints(game.currPos());
			//getSearchListener().notifyBookInfo(bi.first, bi.second);
		}
	}

	@Override
	public boolean isOpponentThinking() {
		return computerThread != null;
	}

	@Override
	public final void startGame() {
		if (getGameMode() == null) {
			throw new IllegalStateException("Game mode not set to start new game");
		}

		try {
			startNewGame(null);
		} catch (ChessParseError chessParseError) {
			// not actually possible
			throw new RuntimeException(chessParseError);
		}
	}

	public final void startNewGameFromFENorPGN(String fenPgn) throws ChessParseError {
		startNewGame(fenPgn);
	}


	private boolean isGameStarting;

	private void startNewGame(@Nullable String fenPgn) throws ChessParseError {
		if (getGameTextListener() == null) {
			throw new IllegalStateException("Game text listener must be initialized");
		} else if (gameMode == null) {
			throw new IllegalStateException("Must set a game mode to start a new game");
		} else if (computerThread != null || isAnalyzing()) {
			throw new IllegalStateException("UCI thread is already running");
		}

		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "Starting new game with mode " + gameMode);
		}

		isGameResumed = false;
		EnginePlayer.getInstance().clearTT();

		setPlayerNames(game);

		getGameTextListener().clear();

		if (fenPgn == null) {
			game = new Game(getGameTextListener(), timeControl,
					movesPerSession, timeIncrement);
		} else {
			game = createGameFromFENorPGN(fenPgn);
		}

		isGameStarting = true;
		postEvent(new Runnable() {
			@Override
			public void run() {
				isGameStarting = false;
				getGui().onGameStarted();
			}
		});
	}

	@Override
	public boolean isGameStarting() {
		return isGameStarting;
	}

	@Override
	public boolean isGameResumed() {
		return getGameMode() != null && getGame() != null && isGameResumed;
	}

	@Override
	public boolean canResumeGame() {
		return !(isGameStarting() || isGameResumed());
	}

	@Override
	public void resumeGame() {
		if (game == null) {
			throw new IllegalStateException("Game hasn't been initialized yet");
		} else if (isAnalyzing()) {
			throw new IllegalStateException("Cannot be analyzing before resuming game");
		} else if (isGameResumed()) {
			throw new IllegalStateException("Game is already resumed");
		}

		isGameResumed = true;

		updateMoveList();

		updateComputeThreads(true);
		onPositionChanged();
		updateGamePaused();

		getGui().setStatusString(getStatusText());

		postEvent(new Runnable() {
			@Override
			public void run() {
				getGui().onGameResumed();
			}
		});
	}

	@Override
	public void pauseGame() {
		setGuiPaused(true);
		stopComputerThinking();
		stopAnalysis();
	}

	@Override
	public void stopGame(boolean withCallback) {
		if (isGameResumed()) {
			pauseGame();
		}

		shutdownEngine();
		gameMode = null;
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

	@Deprecated
	private void setGuiPaused(final boolean paused) {
		isGameResumed = !paused;
		updateGamePaused();

		postEvent(new Runnable() {
			@Override
			public void run() {
				if (!isGameResumed()) {
					getGui().onGamePaused();
				}
			}
		});
	}

	@Deprecated
	private void updateGamePaused() {
		if (gameMode == null || game == null) {
			return;
		}

		boolean gamePaused = (gameMode == GameMode.ANALYSIS)
				|| (isPlayerTurn() && !isGameResumed());
		game.setGamePaused(gamePaused);
		updateRemainingTime();
	}

	@Deprecated
	private void updateComputeThreads(boolean clearPV) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			throw new IllegalStateException("Cannot update compute threads when not on main thread");
		} else if (gameMode == null) {
			throw new IllegalStateException("Cannot update compute threads without a game mode");
		}

		boolean analysis = gameMode == GameMode.ANALYSIS;
		boolean isComputerTurn = !isPlayerTurn();

		if (!analysis) {
			stopAnalysis();
		}

		if (!isComputerTurn) {
			stopComputerThinking();
		}

		if (clearPV) {
			updateBookHints();
		}

		if (analysis) {
			startAnalysis();
		}

		if (isComputerTurn) {
			startComputerThinking();
		}
	}

	@Nullable
	@Override
	public GameMode getGameMode() {
		return gameMode;
	}

	/**
	 * Set game mode.
	 */
	public final void setGameMode(@NonNull GameMode newMode) {
		if (game == null) {
			this.gameMode = newMode;
			return;
		}

		if (isGameActive()) {
			throw new IllegalStateException("Cannot change game mode when game is active");
		}

		gameMode = newMode;

		if (!gameMode.playerWhite() || !gameMode.playerBlack()) {
			setPlayerNames(game);
		}
	}

	public void switchToAnalysisMode() {
		gameMode = GameMode.ANALYSIS;
		stopComputerThinking();
		startAnalysis();
	}

	public void switchToComputerPlayMode() {
		gameMode = getGame().currPos().whiteMove ? GameMode.PLAYER_WHITE : GameMode.PLAYER_BLACK;
		stopAnalysis();

		// once the analysis was stopped, prepare the engine to play again
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				EnginePlayer.prepareInstance();
				return null;
			}
		}.execute();
	}

	private static String getFullBookFileName(String bookFile) {
		if (bookFile.length() > 0) {
			File extDir = Environment.getExternalStorageDirectory();
			String sep = File.separator;
			bookFile = extDir.getAbsolutePath() + sep + DarkKnightActivity.BOOK_DIR + sep
					+ bookFile;
		}

		return bookFile;
	}

	private void setPlayerNames(Game game) {
		if (gameMode != null && game != null) {
			String engine = EnginePlayer.getInstance().getEngineName();
			String white = gameMode.playerWhite() ? "Player" : engine;
			String black = gameMode.playerBlack() ? "Player" : engine;
			game.getTree().setPlayerNames(white, black);
		}
	}

	@Override
	public final void restoreGame(@NonNull GameMode gameMode, byte[] data) {
		if (this.gameMode != null) {
			throw new IllegalStateException("Cannot restore game once a game mode is set");
		}

		setGameMode(gameMode);

		try {
			game = new Game(data, getGameTextListener(), timeControl, movesPerSession, timeIncrement);
		} catch (IOException | ChessParseError e) {
			throw new RuntimeException(e);
		}

		updateMoveList();

		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "Restored game with type " + this.gameMode);
		}

		postEvent(new Runnable() {
			@Override
			public void run() {
				getGui().onGameRestored();
			}
		});
	}

	private Game createGameFromFENorPGN(String fenPgn) throws ChessParseError {
		Game newGame = new Game(getGameTextListener(), timeControl,
				movesPerSession, timeIncrement);
		try {
			Position pos = TextIO.readFEN(fenPgn);
			newGame.setPos(pos);
			setPlayerNames(newGame);
		} catch (ChessParseError e) {
			// Try read as PGN instead
			if (!newGame.readPGN(fenPgn, getPGNOptions())) {
				throw e;
			}
		}

		return newGame;
	}

	@Deprecated
	public void setFENOrPGN(String fenPgn) throws ChessParseError {
		if (getGameTextListener() != null) {
			getGameTextListener().clear();
		}

		Game newGame = new Game(getGameTextListener(), timeControl,
				movesPerSession, timeIncrement);
		try {
			Position pos = TextIO.readFEN(fenPgn);
			newGame.setPos(pos);
			setPlayerNames(newGame);
		} catch (ChessParseError e) {
			// Try read as PGN instead
			if (!newGame.readPGN(fenPgn, getPGNOptions())) {
				throw e;
			}
		}

		game = newGame;

		updateGamePaused();
		stopAnalysis();
		stopComputerThinking();
		EnginePlayer.getInstance().clearTT();
	}

	private void undoMoveNoUpdate() {
		if (game == null || !canUndoMove()) {
			return;
		}

		game.undoMove();
		if (!isPlayerTurn()) {
			if (game.getLastMove() != null) {
				game.undoMove();
				if (!isPlayerTurn()) {
					game.redoMove();
				}
			} else {
				// Don't undo first white move if playing black vs computer,
				// because that would cause computer to immediately make
				// a new move and the whole redo history will be lost.
				if (gameMode.playerWhite() || gameMode.playerBlack())
					game.redoMove();
			}
		}
	}

	public final void undoMove() {
		if (game == null || !canUndoMove()) {
			return;
		}

		final Move lastMove = game.getLastMove();

		undoMoveNoUpdate();
		updateMoveList();

		GUIInterface guiInterface = getGui();
		guiInterface.onThinkingInfoChanged(null);
		guiInterface.onMoveUnmade(lastMove);

		stopAnalysis();
		if (getGameMode() == GameMode.ANALYSIS) {
			startAnalysisDelayed(300);
		} else {
			resetComputerInstanceAsync();
		}
	}

	private void redoMoveNoUpdate() {
		if (game == null || !game.canRedoMove()) {
			return;
		}

		game.redoMove();
		if (!isPlayerTurn() && game.canRedoMove()) {
			game.redoMove();
			if (!isPlayerTurn()) {
				game.undoMove();
			}
		}
	}

	public final void redoMove() {
		if (game == null || !canRedoMove()) {
			return;
		}

		redoMoveNoUpdate();
		updateMoveList();

		GUIInterface guiInterface = getGui();
		guiInterface.onThinkingInfoChanged(null);
		guiInterface.onMoveRemade(game.getLastMove());

		stopAnalysis();
		if (getGameMode() == GameMode.ANALYSIS) {
			startAnalysisDelayed(300);
		} else {
			resetComputerInstanceAsync();
		}
	}

	/**
	 * Start analysis thread, but with a delay. This is important to prevent massive CPU overuse
	 * when button-mashing for going forward and backward in the game tree.
	 */
	private void startAnalysisDelayed(final int delay) {
		mDelayedStartAnalysisTask = new AsyncTask<Void, Void, Void>() {
			private boolean canStartAnalysis = true;

			@Override
			protected Void doInBackground(Void... params) {
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {
					canStartAnalysis = false;
				}

				return null;
			}

			@Override
			protected void onPostExecute(Void aVoid) {
				if (canStartAnalysis && !isCancelled()) {
					mDelayedStartAnalysisTask = null;
					startAnalysis();
				}
			}
		};

		mDelayedStartAnalysisTask.execute();
	}

	private void cancelStartAnalysisDelayed() {
		if (mDelayedStartAnalysisTask != null) {
			mDelayedStartAnalysisTask.cancel(true);
			mDelayedStartAnalysisTask = null;
		}
	}

	private AsyncTask<Void, Void, Void> mDelayedStartAnalysisTask;

	private void resetComputerInstanceAsync() {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				stopAnalysis();
				stopComputerThinking();
				EnginePlayer.prepareInstance();
				return null;
			}

			@Override
			protected void onPreExecute() {
				GUIInterface guiInterface = getGui();
				guiInterface.onThinkingInfoChanged(null);
			}

			@Override
			protected void onPostExecute(Void aVoid) {
				updateComputeThreads(true);
			}
		}.execute();
	}

	public final int numVariations() {
		return game == null ? 0 : game.numVariations();
	}

	public final void tryPlayMove(Move m) {
		if (!isPlayerTurn()) {
			throw new IllegalStateException();
		}

		if (doMove(m)) {
			if (isAnalyzing()) {
				isAnalysisQuickPause = true;
			}

			stopAnalysis();
			stopComputerThinking();
			onMoveMade();

			if (getGameMode() == GameMode.ANALYSIS) {
				startAnalysisDelayed(100);
				isAnalysisQuickPause = false;
			} else {
				updateComputeThreads(true);
			}
		} else if (getPromoteMove() == null) {
			getGui().onInvalidMoveRejected(m);
		}
	}

	@Override
	public void acceptDrawOffer() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void declineDrawOffer() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getStatusText() {
		if (game == null) {
			return null;
		}

		String str = Integer.valueOf(game.currPos().fullMoveCounter).toString();
		str += game.currPos().whiteMove ? ". White's move" : "... Black's move";

		if (computerThread != null) {
			str += " (thinking)";
		}

		if (isAnalyzing()) {
			str += " (analyzing)";
		}

		if (game.getGameStatus() != Status.ALIVE) {
			str = game.getGameStateString();
		}

		return str;
	}

	@Override
	protected void onMoveMade() {
		super.onMoveMade();

		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "onMoveMade()");
		}

		updateRemainingTime();
	}

	@Override
	protected void onGameStatusChanged(Status status) {
		if (status != Status.ALIVE) {
			isGameResumed = false;
			stopComputerThinking();
			stopAnalysis();
		}
	}

	final public void updateRemainingTime() {
		// Update remaining time
//		long now = System.currentTimeMillis();
//		long wTime = game.getTimeController().getRemainingTime(true, now);
//		long bTime = game.getTimeController().getRemainingTime(false, now);
//		long nextUpdate = 0;
//		if (game.getTimeController().clockRunning()) {
//			long t = game.currPos().whiteMove ? wTime : bTime;
//			nextUpdate = (t % 1000);
//			if (nextUpdate < 0)
//				nextUpdate += 1000;
//			nextUpdate += 1;
//		}
//
//		getGui().setRemainingTime(wTime, bTime, nextUpdate);
	}

	private synchronized void startComputerThinking() {
		if (analysisThread != null) {
			return;
		}

		if (game == null || game.getGameStatus() != Status.ALIVE) {
			return;
		}

		if (computerThread != null) {
			return;
		}

		//ss = new SearchStatus();
		final Pair<Position, ArrayList<Move>> ph = game.getUCIHistory();
		final Game g = game;
		final boolean haveDrawOffer = g.haveDrawOffer();
		final Position currPos = new Position(g.currPos());
		long now = System.currentTimeMillis();
		//final int wTime = game.getTimeController().getRemainingTime(true, now);
		//final int bTime = game.getTimeController().getRemainingTime(false, now);
		final int inc = game.getTimeController().getIncrement();
		final int movesToGo = game.getTimeController().getMovesToTC();

		computerThread = new ComputerMoveSelectionThread(EnginePlayer.getInstance(), ph, currPos, haveDrawOffer,
				2000, 2000, inc, movesToGo);
		computerThread.execute();
	}

	protected void onEngineMoveMade(@NonNull String cmd) {
		if (isPlayerTurn()) {
			throw new IllegalStateException("Engine tried to make move while player was playing: " + cmd);
		} else if (game == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (BuildConfig.DEBUG) {
			Log.i(getClass().getSimpleName(), "onEngineMoveMade(" + cmd + ")");
		}

		game.processString(cmd);
		updateGamePaused();
		stopComputerThinking();
		stopAnalysis();
		updateComputeThreads(true);
		onMoveMade();
	}

	private synchronized void stopComputerThinking() {
		if (computerThread == null) {
			return;
		}

		computerThread.stop();

		int elapsed = 0;

		while (computerThread != null) {
			if (BuildConfig.DEBUG) {
				Log.i(getClass().getSimpleName(), "Waiting for computer thread to end");
			}

			if (elapsed > 500) {
				computerThread.cancel(true);
				computerThread = null;
				if (BuildConfig.DEBUG) {
					Log.w(getClass().getSimpleName(), "Computer thread forcibly terminated");
				}
			}

			try {
				Thread.sleep(100);
				elapsed += 100;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private synchronized void startAnalysis() {
		if (gameMode != GameMode.ANALYSIS || game == null
				|| game.getTree().getCurrentGameState() != Game.Status.ALIVE) {
			return;
		}

		if (computerThread != null) {
			throw new IllegalStateException("Cannot start analysis while computer thread is running");
		} else if (analysisThread != null) {
			throw new IllegalStateException("Analysis already started");
		} else if (mDelayedStartAnalysisTask != null) {
			throw new IllegalStateException("Analysis is already being started with a delay");
		} else if (isGameStarting()) {
			throw new IllegalStateException("Cannot start analysis when game is starting");
		}

		final Pair<Position, ArrayList<Move>> ph = game.getUCIHistory();
		final boolean haveDrawOffer = game.haveDrawOffer();
		final Position currPos = new Position(game.currPos());

		analysisThread = new AnalysisThread(EnginePlayer.getInstance(), ph, currPos, haveDrawOffer);
		analysisThread.execute();
	}

	private synchronized void stopAnalysis() {
		cancelStartAnalysisDelayed();

		if (analysisThread == null) {
			return;
		}

		int elapsed = 0;

		analysisThread.stop();
		while (analysisThread != null) {
			if (elapsed > 2000) {
				Log.e(getClass().getSimpleName(), "Analysis thread overheated");
				analysisThread.cancel(true);

				getGui().onAnalysisInterrupted();
			}

			if (elapsed > 5000) {
				Log.e(getClass().getSimpleName(), "Analysis thread super-overheated");
				android.os.Process.killProcess(android.os.Process.myPid());
			}

			try {
				Thread.sleep(10);
				elapsed += 10;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		updateStatusText();
	}

	private void updateStatusText() {
		getGui().setStatusString(getStatusText());
	}

	public void stopSearch() {
		if (computerThread != null) {
			computerThread.stop();
		}
	}


	protected abstract class EngineTaskThread<R> extends AsyncTask<Void, ThinkingInfo, R> {
		protected final EnginePlayer enginePlayer;
		protected final Pair<Position, ArrayList<Move>> ph;
		protected final Position currPos;
		protected final boolean haveDrawOffer;

		protected EngineTaskThread(EnginePlayer player, Pair<Position, ArrayList<Move>> ph,
		                           Position currPos, boolean haveDrawOffer) {
			this.enginePlayer = player;
			this.ph = ph;
			this.currPos = currPos;
			this.haveDrawOffer = haveDrawOffer;
		}
	}

	protected class ComputerMoveSelectionThread extends EngineTaskThread<String> {
		final int wTime;
		final int bTime;
		final int inc;
		final int movesToGo;

		protected ComputerMoveSelectionThread(EnginePlayer player, Pair<Position, ArrayList<Move>> ph,
		                                      Position currPos, boolean haveDrawOffer, int wTime,
		                                      int bTime, int inc, int movesToGo) {
			super(player, ph, currPos, haveDrawOffer);
			this.wTime = wTime;
			this.bTime = bTime;
			this.inc = inc;
			this.movesToGo = movesToGo;
		}

		@Override
		protected String doInBackground(Void... params) {
			try {
				return enginePlayer.doSearch(ph.first,
						ph.second, currPos, haveDrawOffer, wTime, bTime,
						inc, movesToGo, maxDepth, new EngineControllerSearchListener() {
							@Override
							protected void onThinkingInfoChanged(ThinkingInfo thinkingInfo) {
								publishProgress(thinkingInfo);
							}
						});
			} catch (InterruptedException e) {
				return null;
			}
		}

		@Override
		protected void onProgressUpdate(ThinkingInfo... values) {
			getGui().onThinkingInfoChanged(values[0]);
		}

		@Override
		protected void onPreExecute() {
			if (isAnalyzing() || (computerThread != null && computerThread != this)) {
				throw new IllegalStateException("Different UCI thread already started");
			}

			computerThread = this;
			Log.i(getClass().getSimpleName(), "Computer move selection thread started");
			updateStatusText();

			getGui().onOpponentBeganThinking();
		}

		@Override
		protected void onPostExecute(String s) {
			computerThread = null;
			Log.i(getClass().getSimpleName(), "Computer move selection thread stopped");

			if (s != null && !s.equals("")) {
				onEngineMoveMade(s);
			} else if (s != null && s.equals("")) {
				getGui().onAnalysisInterrupted();
			}

			getGui().onOpponentStoppedThinking();
		}

		protected void stop() {
			enginePlayer.stopSearch();
		}
	}

	protected class AnalysisThread extends EngineTaskThread<String> {
		protected AnalysisThread(EnginePlayer player, Pair<Position, ArrayList<Move>> ph,
		                         Position currPos, boolean haveDrawOffer) {
			super(player, ph, currPos, haveDrawOffer);
		}

		@Override
		protected String doInBackground(Void... params) {
			if (enginePlayer.isStoppingSearch()) {
				return null;
			}

			String bestMove = null;

			try {
				bestMove = enginePlayer.analyze(ph.first, new EngineControllerSearchListener() {
					@Override
					protected void onThinkingInfoChanged(ThinkingInfo thinkingInfo) {
						publishProgress(thinkingInfo);
					}
				}, ph.second, currPos, haveDrawOffer);
			} catch (InterruptedException e) {
				if (BuildConfig.DEBUG) {
					e.printStackTrace();
				}
			}

			// set the analysis thread to null here since otherwise waiting methods will never allow
			// onPostExecute() to happen
			analysisThread = null;
			return bestMove;
		}

		@Override
		protected void onProgressUpdate(ThinkingInfo... values) {
			getGui().onThinkingInfoChanged(values[0]);
		}

		@Override
		protected void onPreExecute() {
			if ((analysisThread != null && analysisThread != this) || computerThread != null) {
				throw new IllegalStateException("Different UCI thread is already executing");
			} else if (isGameStarting) {
				throw new IllegalStateException("Cannot start analysis while game is starting");
			}

			analysisThread = this;
			isAnalysisQuickPause = false;

			if (BuildConfig.DEBUG) {
				Log.i(getClass().getSimpleName(), "Analysis thread started");
			}

			updateStatusText();
		}

		@Override
		protected void onPostExecute(String s) {
			if (BuildConfig.DEBUG) {
				Log.i(getClass().getSimpleName(), "Analysis thread stopped");
			}

			getGui().onThinkingInfoChanged(null);
		}

		protected void stop() {
			// stop the search
			enginePlayer.stopSearch();
		}
	}

	private void shutdownEngine() {
		stopComputerThinking();
		stopAnalysis();
		EnginePlayer.shutdownEngine();
	}

	public final void resignGame() {
		if (game == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (game.getGameStatus() == Game.Status.ALIVE) {
			game.processString("resign");
			onMoveMade();
		}
	}

	@Override
	public boolean isAnalyzing() {
		return analysisThread != null || mDelayedStartAnalysisTask != null || isAnalysisQuickPause;
	}

	@Deprecated
	public boolean isAnalysisQuickPause() {
		return isAnalysisQuickPause;
	}


	public interface AsyncGameStateCheckListener {
		void onGameStateReceived(EngineController controller, Game.Status status);
	}

	public final void checkGameStateAsync(final AsyncGameStateCheckListener listener) {
		new GameStateCheckTask() {
			@Override
			protected void onPostExecute(Game.Status status) {
				listener.onGameStateReceived(EngineController.this, status);
			}
		}.execute();
	}

	private class GameStateCheckTask extends AsyncTask<Void, Void, Status> {
		@Override
		protected Game.Status doInBackground(Void... params) {
			if (getGame() == null) {
				return null;
			} else {
				return getGame().getTree().getEndGameState();
			}

		}
	}
}
