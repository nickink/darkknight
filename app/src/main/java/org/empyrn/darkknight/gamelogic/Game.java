package org.empyrn.darkknight.gamelogic;

import android.support.annotation.NonNull;

import org.empyrn.darkknight.PGNOptions;
import org.empyrn.darkknight.gamelogic.GameTree.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Game {
	private @NonNull final GameTree tree;

	private boolean pendingDrawOffer;
	private TimeControl timeController;
	private boolean gamePaused;

	static GameTree makeGameTreeWithListener(PgnToken.PgnTokenReceiver gameTextListener) {
		GameTree tree = new GameTree();
		tree.setGameStateListener(gameTextListener);
		return tree;
	}

	static GameTree makeGameTreeWithListener(byte[] data, PgnToken.PgnTokenReceiver gameTextListener) {
		try {
			GameTree tree = new GameTree(data);
			tree.setGameStateListener(gameTextListener);
			return tree;
		} catch (IOException | ChessParseError e) {
			throw new RuntimeException(e);
		}
	}


	public Game(PgnToken.PgnTokenReceiver gameTextListener,
	            int timeControl, int movesPerSession, int timeIncrement) {
		this(makeGameTreeWithListener(gameTextListener), timeControl, movesPerSession,
				timeIncrement);
	}

	public Game(byte[] data, PgnToken.PgnTokenReceiver gameTextListener,
	            int timeControl, int movesPerSession, int timeIncrement)
			throws IOException, ChessParseError {
		this(makeGameTreeWithListener(data, gameTextListener), timeControl, movesPerSession,
				timeIncrement);
	}

	private Game(@NonNull GameTree tree,
	             int timeControl, int movesPerSession, int timeIncrement) {
		this.tree = tree;
		timeController = new TimeControl();
		getTimeController().setTimeControl(timeControl, movesPerSession, timeIncrement);
		gamePaused = false;
		getTimeController().reset();
		pendingDrawOffer = false;
		updateTimeControl(true);
	}

	public final void setGamePaused(boolean gamePaused) {
		if (gamePaused != this.gamePaused) {
			this.gamePaused = gamePaused;
			updateTimeControl(false);
		}
	}

	final void setPos(Position pos) {
		getTree().setStartPos(new Position(pos));
		updateTimeControl(false);
	}

	final boolean readPGN(String pgn, PGNOptions options) throws ChessParseError {
		boolean ret = getTree().readPGN(pgn, options);
		if (ret)
			updateTimeControl(false);
		return ret;
	}

	public final Position currPos() {
		return getTree().currentPos;
	}

	/**
	 * Perform a move.
	 *
	 * @param m the move to perform
	 * @return whether the move could be performed (was legal)
	 */
	public boolean performMove(Move m) {
		String strMove = TextIO.moveToString(currPos(), m, false);
		return processString(strMove);
	}

	/**
	 * Update the game state according to move/command string from a player.
	 *
	 * @param str The move or command to process.
	 * @return True if str was understood, false otherwise.
	 * @deprecated This method is too general and needs to be split up into other methods.
	 */
	@Deprecated
	public final boolean processString(String str) {
		if (getGameStatus() != Status.ALIVE) {
			return false;
		}

		if (str.startsWith("draw ")) {
			String drawCmd = str.substring(str.indexOf(" ") + 1);
			handleDrawCmd(drawCmd);
			return true;
		} else if (str.equals("resign")) {
			addToGameTree(new Move(0, 0, 0), "resign");
			return true;
		}

		Move m = TextIO.UCIstringToMove(str);
		if (m != null) {
			Set<Move> legalMoves = MoveGenerator.INSTANCE.generateLegalMoves(currPos());
			boolean legal = false;
			for (Move move : legalMoves) {
				if (m.equals(move)) {
					legal = true;
					break;
				}
			}

			if (!legal) {
				m = null;
			}
		}

		if (m == null) {
			m = TextIO.stringToMove(currPos(), str);
		}

		if (m == null) {
			return false;
		}

		addToGameTree(m, pendingDrawOffer ? "draw offer" : "");
		return true;
	}

	private void addToGameTree(Move m, String playerAction) {
		if (m.equals(new Move(0, 0, 0))) { // Don't create more than one null move at a node
			List<Move> varMoves = getTree().variations();
			for (int i = varMoves.size() - 1; i >= 0; i--) {
				if (varMoves.get(i).equals(m)) {
					getTree().deleteVariation(i);
				}
			}
		}

		List<Move> varMoves = getTree().variations();
		boolean movePresent = false;
		int varNo;
		for (varNo = 0; varNo < varMoves.size(); varNo++) {
			if (varMoves.get(varNo).equals(m)) {
				movePresent = true;
				break;
			}
		}

		if (!movePresent) {
			String moveStr = TextIO.moveToUCIString(m);
			varNo = getTree().addMove(moveStr, playerAction, 0, "", "");
		}
		getTree().reorderVariation(varNo, 0);
		getTree().goForward(0);
		int remaining = getTimeController().moveMade(System.currentTimeMillis());
		getTree().setRemainingTime(remaining);
		updateTimeControl(true);
		pendingDrawOffer = false;
	}

	private void updateTimeControl(boolean discardElapsed) {
		int move = currPos().fullMoveCounter;
		boolean wtm = currPos().whiteMove;
		if (discardElapsed || (move != getTimeController().currentMove) || (wtm != getTimeController().whiteToMove)) {
			int initialTime = getTimeController().getInitialTime();
			int whiteBaseTime = getTree().getRemainingTime(true, initialTime);
			int blackBaseTime = getTree().getRemainingTime(false, initialTime);
			getTimeController().setCurrentMove(move, wtm, whiteBaseTime, blackBaseTime);
		}
		long now = System.currentTimeMillis();
		if (gamePaused || (getGameStatus() != Status.ALIVE)) {
			getTimeController().stopTimer(now);
		} else {
			getTimeController().startTimer(now);
		}
	}

	public final String getGameStateString() {
		switch (getGameStatus()) {
			case ALIVE:
				return "";
			case WHITE_MATE:
				return "Game over, white mates!";
			case BLACK_MATE:
				return "Game over, black mates!";
			case WHITE_STALEMATE:
			case BLACK_STALEMATE:
				return "Game over, draw by stalemate!";
			case DRAW_REP: {
				String ret = "Game over, draw by repetition!";
				String drawInfo = getTree().getGameStateInfo();
				if (drawInfo.length() > 0) {
					ret = ret + " [" + drawInfo + "]";
				}
				return ret;
			}
			case DRAW_50: {
				String ret = "Game over, draw by 50 move rule!";
				String drawInfo = getTree().getGameStateInfo();
				if (drawInfo.length() > 0) {
					ret = ret + " [" + drawInfo + "]";
				}
				return ret;
			}
			case DRAW_NO_MATE:
				return "Game over, draw by impossibility of mate!";
			case DRAW_AGREE:
				return "Game over, draw by agreement!";
			case RESIGN_WHITE:
				return "Game over, white resigns!";
			case RESIGN_BLACK:
				return "Game over, black resigns!";
			default:
				throw new RuntimeException();
		}
	}

	/**
	 * Get the last played move, or null if no moves played yet.
	 */
	public final Move getLastMove() {
		return getTree().currentNode.move;
	}

	/**
	 * Return true if there is a move to redo.
	 */
	public final boolean canRedoMove() {
		int nVar = getTree().variations().size();
		return nVar > 0;
	}

	public final int numVariations() {
		if (getTree().currentNode == getTree().rootNode)
			return 1;
		getTree().goBack();
		int nChildren = getTree().variations().size();
		getTree().goForward(-1);
		return nChildren;
	}

	public final void changeVariation(int delta) {
		if (getTree().currentNode == getTree().rootNode) {
			return;
		}

		getTree().goBack();
		int defChild = getTree().currentNode.defaultChild;
		int nChildren = getTree().variations().size();
		int newChild = defChild + delta;
		newChild = Math.max(newChild, 0);
		newChild = Math.min(newChild, nChildren - 1);
		getTree().goForward(newChild);
		pendingDrawOffer = false;
		updateTimeControl(true);
	}

	public final void removeVariation() {
		if (numVariations() <= 1) {
			return;
		}

		getTree().goBack();
		int defChild = getTree().currentNode.defaultChild;
		getTree().deleteVariation(defChild);
		getTree().goForward(-1);
		pendingDrawOffer = false;
		updateTimeControl(true);
	}

	public @NonNull GameTree getTree() {
		return tree;
	}

	public TimeControl getTimeController() {
		return timeController;
	}

	public enum Status {
		ALIVE,
		WHITE_MATE,         // White mates
		BLACK_MATE,         // Black mates
		WHITE_STALEMATE,    // White is stalemated
		BLACK_STALEMATE,    // Black is stalemated
		DRAW_REP,           // Draw by 3-fold repetition
		DRAW_50,            // Draw by 50 move rule
		DRAW_NO_MATE,       // Draw by impossibility of check mate
		DRAW_AGREE,         // Draw by agreement
		RESIGN_WHITE,       // White resigns
		RESIGN_BLACK        // Black resigns
	}

	/**
	 * Get the current state (draw, mate, ongoing, etc) of the game.
	 */
	public final Status getGameStatus() {
		return getTree().getCurrentGameState();
	}

	/**
	 * Check if a draw offer is available.
	 *
	 * @return True if the current player has the option to accept a draw offer.
	 */
	public final boolean haveDrawOffer() {
		return getTree().currentNode.playerAction.equals("draw offer");
	}

	public final void undoMove() {
		Move m = getTree().currentNode.move;
		if (m != null) {
			getTree().goBack();
			pendingDrawOffer = false;
			updateTimeControl(true);
		}
	}

	public final void redoMove() {
		if (!canRedoMove()) {
			return;
		}

		getTree().goForward(-1);
		pendingDrawOffer = false;
		updateTimeControl(true);
	}


	/**
	 * Return the last zeroing position and a list of moves
	 * to go from that position to the current position.
	 */
	public final Pair<Position, ArrayList<Move>> getUCIHistory() {
		Pair<List<Node>, Integer> ml = getTree().getMoveList();
		List<Node> moveList = ml.first;
		Position pos = new Position(getTree().startPos);
		ArrayList<Move> mList = new ArrayList<>();
		Position currPos = new Position(pos);
		UndoInfo ui = new UndoInfo();
		int nMoves = ml.second;
		for (int i = 0; i < nMoves; i++) {
			Node n = moveList.get(i);
			mList.add(n.move);
			currPos.makeMove(n.move, ui);
			if (currPos.halfMoveClock == 0) {
				pos = new Position(currPos);
				mList.clear();
			}
		}

		return new Pair<>(pos, mList);
	}

	private void handleDrawCmd(String drawCmd) {
		Position pos = getTree().currentPos;
		if (drawCmd.startsWith("rep") || drawCmd.startsWith("50")) {
			boolean rep = drawCmd.startsWith("rep");
			Move m = null;
			String ms = drawCmd.substring(drawCmd.indexOf(" ") + 1);
			if (ms.length() > 0) {
				m = TextIO.stringToMove(pos, ms);
			}
			boolean valid;
			if (rep) {
				valid = false;
				UndoInfo ui = new UndoInfo();
				int repetitions = 0;
				Position posToCompare = new Position(getTree().currentPos);
				if (m != null) {
					posToCompare.makeMove(m, ui);
					repetitions = 1;
				}
				Pair<List<Node>, Integer> ml = getTree().getMoveList();
				List<Node> moveList = ml.first;
				Position tmpPos = new Position(getTree().startPos);
				if (tmpPos.drawRuleEquals(posToCompare))
					repetitions++;
				int nMoves = ml.second;
				for (int i = 0; i < nMoves; i++) {
					Node n = moveList.get(i);
					tmpPos.makeMove(n.move, ui);
					TextIO.fixupEPSquare(tmpPos);
					if (tmpPos.drawRuleEquals(posToCompare))
						repetitions++;
				}
				if (repetitions >= 3)
					valid = true;
			} else {
				Position tmpPos = new Position(pos);
				if (m != null) {
					UndoInfo ui = new UndoInfo();
					tmpPos.makeMove(m, ui);
				}
				valid = tmpPos.halfMoveClock >= 100;
			}
			if (valid) {
				String playerAction = rep ? "draw rep" : "draw 50";
				if (m != null)
					playerAction += " " + TextIO.moveToString(pos, m, false);
				addToGameTree(new Move(0, 0, 0), playerAction);
			} else {
				pendingDrawOffer = true;
				if (m != null) {
					processString(ms);
				}
			}
		} else if (drawCmd.startsWith("offer ")) {
			pendingDrawOffer = true;
			String ms = drawCmd.substring(drawCmd.indexOf(" ") + 1);
			if (TextIO.stringToMove(pos, ms) != null) {
				processString(ms);
			}
		} else if (drawCmd.equals("accept")) {
			if (haveDrawOffer())
				addToGameTree(new Move(0, 0, 0), "draw accept");
		}
	}
}
