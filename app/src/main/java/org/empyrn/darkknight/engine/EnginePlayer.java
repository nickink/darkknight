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


/**
 * A computer algorithm player.
 */
public class EnginePlayer {
	public final String mEngineName;

	private static NativePipedProcess s_npp = null;

	private final NativePipedProcess npp;
	//private int timeLimit;
	private Book book;
	private boolean newGame = false;

	private static volatile EnginePlayer playerInstance;


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

	private EnginePlayer() {
		if (s_npp == null) {
			s_npp = new NativePipedProcess();
			s_npp.initialize();
		}

		npp = s_npp;
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

		//timeLimit = 0;
		book = new Book(false);
	}

	@NonNull
	public String getEngineName() {
		return mEngineName;
	}

	@Deprecated
	public final void setBookFileName(String bookFileName) {
		book.setBookFileName(bookFileName);
	}

	/**
	 * Convert a string to tokens by splitting at whitespace characters.
	 */
	private static String[] tokenize(String cmdLine) {
		cmdLine = cmdLine.trim();
		return cmdLine.split("\\s+");
	}

	private void syncReady() {
		npp.writeLineToProcess("isready");
		while (true) {
			// wait for the NPP to send the all clear to start the game
			String s = npp.readLineFromProcess(1000);
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
		if (newGame) {
			newGame = false;
			npp.writeLineToProcess("ucinewgame");
			syncReady();
		}
	}

	/**
	 * Stop the engine process and clear the player from memory.
	 */
	public static synchronized void shutdownEngine() {
		s_npp = null;

		if (playerInstance != null) {
			playerInstance.npp.shutDown();
			playerInstance = null;

			System.err.println("Shut down player instance");
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
		if (wTime < 1) wTime = 1;
		if (bTime < 1) bTime = 1;
		String goStr = String.format("go wtime %d btime %d", wTime, bTime);

		if (maxDepth > 1) {
			goStr += String.format(" depth %d", maxDepth);
		}

		if (inc > 0) {
			goStr += String.format(" winc %d binc %d", inc, inc);
		}

		if (movesToGo > 0) {
			goStr += String.format(" movestogo %d", movesToGo);
		}

		npp.writeLineToProcess(goStr);

		String bestMove = runEngineMonitorLoop(currPos, searchListener);
		shouldStopSearch = false;

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
		} else if (shouldStopSearch) {
			throw new IllegalStateException("stopSearch cannot be true when starting to monitor the engine");
		}

		Log.i(getClass().getSimpleName(), "Monitoring engine on: " + Thread.currentThread().getName());

		clearInfo();

		boolean stopSent = false;

		while (true) {
			if (!npp.isProcessAlive()) {
				// break out of the loop if the NPP has been shut down
				throw new InterruptedException("UCI engine process has been shut down");
			}

			if (shouldStopSearch && !stopSent) {
				Log.i(getClass().getSimpleName(), this + " stopping search");
				// if the engine should stopGame, stopGame it
				npp.writeLineToProcess("stop");
				stopSent = true;
			}

			String s = npp.readLineFromProcess(2000);

			if (s == null || s.length() == 0) {
				Log.e(getClass().getSimpleName(), "Received empty input from engine");
				return null;
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


	private boolean shouldStopSearch = false;

	public final void analyze(Position prevPos, @NonNull SearchListener searchListener,
	                          ArrayList<Move> mList, Position currPos, boolean drawOffer) throws InterruptedException {
		if (!npp.isProcessAlive()) {
			throw new IllegalStateException("Engine process is not initialized");
		}


		shouldStopSearch = false;

		Pair<String, ArrayList<Move>> bi = getBookHints(currPos);
		searchListener.notifyBookInfo(bi.first, bi.second);

		// If no legal moves, there is nothing to analyze
		Set<Move> moves = MoveGenerator.INSTANCE.generateLegalMoves(currPos);
		if (moves.size() == 0) {
			// no legal moves
			return;
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

		runEngineMonitorLoop(currPos, searchListener);
		shouldStopSearch = false;
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

	private static boolean canClaimDraw50(Position pos) {
		return (pos.halfMoveClock >= 100);
	}

	private static boolean canClaimDrawRep(Position pos, long[] posHashList, int posHashListSize, int posHashFirstNew) {
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
		Log.i(getClass().getSimpleName(), this + " received shouldStopSearch");
		shouldStopSearch = true;

		//TODO: remove this once "stopGame" actually stops the engine from thinking
		EnginePlayer.shutdownEngine();
	}
}
