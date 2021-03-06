package org.empyrn.darkknight.engine;

import android.annotation.SuppressLint;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;

import org.empyrn.darkknight.gamelogic.Move;
import org.empyrn.darkknight.gamelogic.MoveGenerator;
import org.empyrn.darkknight.gamelogic.Pair;
import org.empyrn.darkknight.gamelogic.Position;
import org.empyrn.darkknight.gamelogic.SearchListener;
import org.empyrn.darkknight.gamelogic.TextIO;
import org.empyrn.darkknight.gamelogic.UndoInfo;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * A computer algorithm player.
 */
public class EnginePlayer {
	private static NativePipedProcess s_npp = null;
	private static volatile EnginePlayer playerInstance;
	private final NativePipedProcess npp;
	private final AtomicBoolean shouldStopSearch = new AtomicBoolean(false);
	private final String mEngineName;
	private Book book;
	private boolean newGame = false;
	private int statCurrDepth = 0;
	private int statPVDepth = 0;
	private int statScore = 0;
	private boolean statIsMate = false;
	private boolean statUpperBound = false;
	private boolean statLowerBound = false;
	private int statTime = 0;
	private int statNodes = 0;
	private int statNps = 0;
	private ArrayList<String> statPV = new ArrayList<>();
	private String statCurrMove = "";
	private int statCurrMoveNr = 0;
	private boolean depthModified = false;
	private boolean currMoveModified = false;
	private boolean pvModified = false;
	private boolean statsModified = false;


	private EnginePlayer() {
		if (s_npp == null) {
			s_npp = new NativePipedProcess();
			s_npp.initialize();
		}

		npp = s_npp;
		book = new Book(false);


		npp.writeLineToProcess("uci");

		int timeout = 1000;
		String engineName = null;
		while (true) {
			String s = npp.readLineFromProcess(timeout);
			String[] tokens = tokenize(s);
			if (tokens[0].equals("uciok")) {
				break;
			} else if (tokens[0].equals("id")) {
				if (tokens[1].equals("name")) {
					engineName = "";
					for (int i = 2; i < tokens.length; i++) {
						if (engineName.length() > 0) {
							engineName += " ";
						}

						engineName += tokens[i];
					}
				}
			}
		}

		if (engineName == null) {
			throw new IllegalStateException("Engine could not be initialized");
		}

		mEngineName = engineName;
		Log.i(getClass().getSimpleName(), "Created new engine player instance: " + mEngineName);

		npp.writeLineToProcess("setoption name Hash value 16");
		npp.writeLineToProcess("setoption name Ponder value false");
		npp.writeLineToProcess("setoption name Aggressiveness value 200");
		npp.writeLineToProcess("setoption name Space value 200");
		npp.writeLineToProcess("ucinewgame");
		syncReady();
	}

	public static synchronized void prepareInstance() {
		if (playerInstance == null) {
			playerInstance = new EnginePlayer();
		}
	}

	@NonNull
	public static synchronized EnginePlayer getInstance() {
		prepareInstance();
		return playerInstance;
	}

	/**
	 * Convert a string to tokens by splitting at whitespace characters.
	 */
	private static String[] tokenize(String cmdLine) {
		cmdLine = cmdLine.trim();
		return cmdLine.split("\\s+");
	}

	/**
	 * Stop the engine process and clear the player from memory.
	 */
	public static synchronized void shutdownEngine() {
		if (s_npp == null) {
			return;
		}

		s_npp.shutDown();
		while (s_npp.isProcessAlive()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		s_npp = null;

		Log.i(EnginePlayer.class.getSimpleName(), "Shut down engine process");

		if (playerInstance != null) {
			playerInstance = null;

			Log.i(EnginePlayer.class.getSimpleName(), "Removed player instance");
		}
	}

	private static boolean canClaimDraw50(Position pos) {
		return (pos.halfMoveClock >= 100);
	}

	private static boolean canClaimDrawRep(Position pos, long[] posHashList, int posHashListSize,
	                                       int posHashFirstNew) {
		int reps = 0;
		for (int i = posHashListSize - 4; i >= 0; i -= 2) {
			if (pos.zobristHash() == posHashList[i]) {
				reps++;
				if (i >= posHashFirstNew) {
					reps++;
					break;
				}
			}
		}
		return (reps >= 2);
	}

	private boolean isPrepared() {
		return mEngineName != null;
	}

	private void checkPrepared() {
		if (!isPrepared()) {
			throw new IllegalStateException("Engine is not ready");
		}
	}

//	private void prepare() {
//		npp.writeLineToProcess("uci");
//
//		int timeout = 1000;
//		String engineName = null;
//		while (true) {
//			String s = npp.readLineFromProcess(timeout);
//			String[] tokens = tokenize(s);
//			if (tokens[0].equals("uciok")) {
//				break;
//			} else if (tokens[0].equals("id")) {
//				if (tokens[1].equals("name")) {
//					engineName = "";
//					for (int i = 2; i < tokens.length; i++) {
//						if (engineName.length() > 0) {
//							engineName += " ";
//						}
//
//						engineName += tokens[i];
//					}
//				}
//			}
//		}
//
//		if (engineName == null) {
//			throw new IllegalStateException("Engine could not be initialized");
//		}
//
//		mEngineName = engineName;
//		Log.i(getClass().getSimpleName(), "Created new engine player instance: " + mEngineName);
//
//		npp.writeLineToProcess("setoption name Hash value 16");
//		npp.writeLineToProcess("setoption name Ponder value false");
//		npp.writeLineToProcess("setoption name Aggressiveness value 200");
//		npp.writeLineToProcess("setoption name Space value 200");
//		npp.writeLineToProcess("ucinewgame");
//		syncReady();
//	}

	private void prepareIfNeeded() {
//		if (!isPrepared()) {
//			prepare();
//		}
	}

	@NonNull
	public String getEngineName() {
		return mEngineName;
	}

	@Deprecated
	public final void setBookFileName(String bookFileName) {
		book.setBookFileName(bookFileName);
	}

	private void syncReady() {
		npp.writeLineToProcess("isready");
		while (true) {
			// wait for the NPP to send the all clear to start the game
			String s = npp.readLineFromProcess(100);
			if (s != null && s.equals("readyok")) {
				break;
			}
		}
	}

	/**
	 * Clear transposition table.
	 */
	public final void clearTT() {
		newGame = true;
	}

	/**
	 * Send a command to create a new game (clear the transposition table), if the game has been
	 * marked dirty.
	 */
	public final void maybeNewGame() {
		checkPrepared();

		if (newGame) {
			newGame = false;
			npp.writeLineToProcess("ucinewgame");
			syncReady();
		}
	}

	/**
	 * Do a search and return a command from the computer player.
	 * The command can be a valid move string, in which case the move is played
	 * and the turn goes over to the other player. The command can also be a special
	 * command, such as "draw" and "resign".
	 *
	 * @param prevPos An earlier position from the game
	 * @param mList   List of moves to go from the earlier position to the current position.
	 *                This list makes it possible for the computer to correctly handle draw
	 *                by repetition/50 moves.
	 */
	@SuppressLint("DefaultLocale")
	public final String doSearch(Position prevPos, ArrayList<Move> mList, Position currPos,
	                             boolean drawOffer,
	                             int wTime, int bTime, int inc, int movesToGo, int maxDepth,
	                             @NonNull final SearchListener searchListener) throws InterruptedException {
		prepareIfNeeded();
		searchListener.notifyBookInfo("", null);

		// Set up for draw detection
		long[] posHashList = new long[mList.size() + 1];
		int posHashListSize = 0;

		Position p = new Position(prevPos);
		UndoInfo ui = new UndoInfo();
		for (int i = 0; i < mList.size(); i++) {
			posHashList[posHashListSize++] = p.zobristHash();
			p.makeMove(mList.get(i), ui);
		}

		// if there's a book move, play it
		Move bookMove = book.getBookMove(currPos);
		if (bookMove != null && canClaimDraw(currPos, posHashList, posHashListSize, bookMove).equals("")) {
			return TextIO.moveToString(currPos, bookMove, false);
		}

		// if there's only one legal move, play it without searching
		Set<Move> moves = MoveGenerator.INSTANCE.generateLegalMoves(currPos);
		if (moves.size() == 0) {
			return ""; // User set up a position where computer has no valid moves.
		}

		if (moves.size() == 1) {
			Move bestMove = moves.iterator().next();
			if (canClaimDraw(currPos, posHashList, posHashListSize, bestMove).equals("")) {
				return TextIO.moveToUCIString(bestMove);
			}
		}

		StringBuilder posStr = new StringBuilder();
		posStr.append("position fen ");
		posStr.append(TextIO.toFEN(prevPos));
		int nMoves = mList.size();
		if (nMoves > 0) {
			posStr.append(" moves");
			for (int i = 0; i < nMoves; i++) {
				posStr.append(" ");
				posStr.append(TextIO.moveToUCIString(mList.get(i)));
			}
		}

		maybeNewGame();
		npp.writeLineToProcess(posStr.toString());
//		if (wTime < 1) wTime = 1;
//		if (bTime < 1) bTime = 1;
		String goStr = "go";

		if (maxDepth > 1) {
			goStr += String.format(" depth %d", maxDepth);
		}

//		if (inc > 0) {
//			goStr += String.format(" winc %d binc %d", inc, inc);
//		}
//
//		if (movesToGo > 0) {
//			goStr += String.format(" movestogo %d", movesToGo);
//		}

		npp.writeLineToProcess(goStr);

		String bestMove = runEngineMonitorLoop(currPos, searchListener);
		shouldStopSearch.set(false);

		// claim draw if appropriate
		if (statScore <= 0) {
			String drawClaim = canClaimDraw(currPos, posHashList, posHashListSize, TextIO.UCIstringToMove(bestMove));
			if (!drawClaim.equals(""))
				bestMove = drawClaim;
		}

		// accept draw offer if engine is losing
		if (drawOffer && !statIsMate && (statScore <= -300)) {
			bestMove = "draw accept";
		}

		return bestMove;
	}

	/**
	 * Wait for engine to respond with "bestmove". While waiting, monitor and report search info.
	 */
	private String runEngineMonitorLoop(Position pos, @NonNull SearchListener searchListener) throws InterruptedException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new IllegalStateException("Cannot monitor engine on main thread");
		} else if (shouldStopSearch.get()) {
			throw new IllegalStateException("stopSearch cannot be true when starting to monitor the engine");
		}

		Log.i(getClass().getSimpleName(), "Monitoring engine on: " + Thread.currentThread().getName());

		clearInfo();

//		boolean stopSent = false;

		while (true) {
			if (!npp.isProcessAlive()) {
				// break out of the loop if the NPP has been shut down
				throw new InterruptedException("UCI engine process has been shut down");
			}

//			if (shouldStopSearch.get() && !stopSent) {
//				Log.i(getClass().getSimpleName(), this + " stopping search for " + npp.toString());
//				// if the engine should stop, stop it
//				npp.writeLineToProcess("stop");
//				stopSent = true;
//			}

			String s = npp.readLineFromProcess(2000);

			if (s == null || s.length() == 0) {
				Log.e(getClass().getSimpleName(), "Received empty input from engine");
				return "";
			} else {
				Log.d(getClass().getSimpleName(), "Received data from engine: " + s);
			}

			String[] tokens = tokenize(s);
			if (tokens[0].equals("info")) {
				parseInfoCmd(tokens);
				updateThinkingProgress(pos, searchListener);
			} else if (tokens[0].equals("bestmove")) {
				return tokens[1];
			}
		}
	}

	public final Pair<String, ArrayList<Move>> getBookHints(Position pos) {
		Pair<String, ArrayList<Move>> bi = book.getAllBookMoves(pos);
		return new Pair<>(bi.first, bi.second);
	}

	public final boolean isStoppingSearch() {
		return shouldStopSearch.get();
	}

	public final String analyze(Position prevPos, @NonNull SearchListener searchListener,
	                            ArrayList<Move> mList, Position currPos, boolean drawOffer) throws InterruptedException {
		if (!npp.isProcessAlive()) {
			throw new IllegalStateException("Engine process is not initialized");
		} else if (shouldStopSearch.get()) {
			throw new IllegalStateException("shouldStopSearch cannot be true when starting analysis");
		}

		prepareIfNeeded();

		Pair<String, ArrayList<Move>> bi = getBookHints(currPos);
		searchListener.notifyBookInfo(bi.first, bi.second);

		// if no legal moves, there is nothing to analyze
		Set<Move> moves = MoveGenerator.INSTANCE.generateLegalMoves(currPos);
		if (moves.size() == 0) {
			// no legal moves
			return null;
		}

		StringBuilder posStr = new StringBuilder();
		posStr.append("position fen ");
		posStr.append(TextIO.toFEN(prevPos));
		int nMoves = mList.size();
		if (nMoves > 0) {
			posStr.append(" moves");
			for (int i = 0; i < nMoves; i++) {
				posStr.append(" ");
				posStr.append(TextIO.moveToUCIString(mList.get(i)));
			}
		}

		maybeNewGame();
		npp.writeLineToProcess(posStr.toString());
		npp.writeLineToProcess("go infinite");

		return runEngineMonitorLoop(currPos, searchListener);
	}

	/**
	 * Check if a draw claim is allowed, possibly after playing "move".
	 *
	 * @param move The move that may have to be made before claiming draw.
	 * @return The draw string that claims the draw, or empty string if draw claim not valid.
	 */
	private String canClaimDraw(Position pos, long[] posHashList, int posHashListSize, Move move) {
		String drawStr = "";
		if (canClaimDraw50(pos)) {
			drawStr = "draw 50";
		} else if (canClaimDrawRep(pos, posHashList, posHashListSize, posHashListSize)) {
			drawStr = "draw rep";
		} else {
			String strMove = TextIO.moveToString(pos, move, false);
			posHashList[posHashListSize++] = pos.zobristHash();
			UndoInfo ui = new UndoInfo();
			pos.makeMove(move, ui);
			if (canClaimDraw50(pos)) {
				drawStr = "draw 50 " + strMove;
			} else if (canClaimDrawRep(pos, posHashList, posHashListSize, posHashListSize)) {
				drawStr = "draw rep " + strMove;
			}
			pos.unMakeMove(move, ui);
		}

		return drawStr;
	}

	private void clearInfo() {
		depthModified = false;
		currMoveModified = false;
		pvModified = false;
		statsModified = false;
	}

	private void parseInfoCmd(String[] tokens) {
		try {
			int nTokens = tokens.length;
			int i = 1;
			while (i < nTokens - 1) {
				String is = tokens[i++];
				switch (is) {
					case "depth":
						statCurrDepth = Integer.parseInt(tokens[i++]);
						depthModified = true;
						break;
					case "currmove":
						statCurrMove = tokens[i++];
						currMoveModified = true;
						break;
					case "currmovenumber":
						statCurrMoveNr = Integer.parseInt(tokens[i++]);
						currMoveModified = true;
						break;
					case "time":
						statTime = Integer.parseInt(tokens[i++]);
						statsModified = true;
						break;
					case "nodes":
						statNodes = Integer.parseInt(tokens[i++]);
						statsModified = true;
						break;
					case "nps":
						statNps = Integer.parseInt(tokens[i++]);
						statsModified = true;
						break;
					case "pv":
						statPV.clear();
						while (i < nTokens)
							statPV.add(tokens[i++]);
						pvModified = true;
						statPVDepth = statCurrDepth;
						break;
					case "score":
						statIsMate = tokens[i++].equals("mate");
						statScore = Integer.parseInt(tokens[i++]);
						statUpperBound = false;
						statLowerBound = false;
						if (tokens[i].equals("upperbound")) {
							statUpperBound = true;
							i++;
						} else if (tokens[i].equals("lowerbound")) {
							statLowerBound = true;
							i++;
						}
						pvModified = true;
						break;
				}
			}
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Notify GUI about search statistics.
	 */
	private void updateThinkingProgress(Position pos, @NonNull SearchListener searchListener) {
		if (depthModified) {
			searchListener.notifyDepth(statCurrDepth);
			depthModified = false;
		}

		if (currMoveModified) {
			Move m = TextIO.UCIstringToMove(statCurrMove);
			searchListener.notifyCurrMove(pos, m, statCurrMoveNr);
			currMoveModified = false;
		}

		if (pvModified) {
			ArrayList<Move> moves = new ArrayList<Move>();
			int nMoves = statPV.size();
			for (int i = 0; i < nMoves; i++)
				moves.add(TextIO.UCIstringToMove(statPV.get(i)));
			searchListener.notifyPV(pos, statPVDepth, statScore, statTime, statNodes, statNps,
					statIsMate, statUpperBound, statLowerBound, moves);
			pvModified = false;
		}

		if (statsModified) {
			searchListener.notifyStats(statNodes, statNps, statTime);
			statsModified = false;
		}
	}

	public final void stopSearch() {
		if (!isPrepared()) {
			return;
		}

//		if (shouldStopSearch.get()) {
//			// search is already being stopped
//			return;
//		}

		Log.i(getClass().getSimpleName(), this + " received shouldStopSearch for " + npp.toString());
		synchronized (shouldStopSearch) {
			shouldStopSearch.set(true);

			//TODO: remove this once "stop" actually stops the engine from thinking
			EnginePlayer.shutdownEngine();

			while (npp.isProcessAlive()) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			shouldStopSearch.set(false);
		}
	}
}
