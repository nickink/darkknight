package org.empyrn.darkknight.gamelogic;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.empyrn.darkknight.GUIInterface;
import org.empyrn.darkknight.GameMode;
import org.empyrn.darkknight.PGNOptions;
import org.empyrn.darkknight.engine.ThinkingInfo;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;

/**
 * Created by nick on 10/3/15.
 */
public abstract class AbstractGameController implements GameController {

	// use a weak reference to avoid potential memory leaks to activities
	private WeakReference<GUIInterface> mGuiInterface;

	private PgnToken.PgnTokenReceiver mPgnTokenReceiver;


	protected final void postEvent(Runnable r) {
		new Handler(Looper.getMainLooper()).post(r);
	}

	public final @Nullable PgnToken.PgnTokenReceiver getGameTextListener() {
		return mPgnTokenReceiver;
	}

	public final void setGameTextListener(@Nullable PgnToken.PgnTokenReceiver pgnTokenReceiver) {
		if (hasGame()) {
			throw new IllegalStateException("Cannot set a game text listener when a game has already started");
		}

		mPgnTokenReceiver = pgnTokenReceiver;
	}

	@NonNull
	public final GUIInterface getGui() {
		if (mGuiInterface == null || mGuiInterface.get() == null) {
			return DUMMY_CALLBACK;
		} else {
			return mGuiInterface.get();
		}
	}

	// dummy callback interface to avoid null checks everywhere
	private static final GUIInterface DUMMY_CALLBACK = new GUIInterface() {
		@Override
		public void onPositionChanged(Position newPosition, String variantInfo, List<Move> variantMoves) {

		}

		@Override
		public void onGameStarted() {

		}

		@Override
		public void onGameRestored() {

		}

		@Override
		public void onGameResumed() {

		}

		@Override
		public void onGamePaused() {

		}

		@Override
		public void onGameStopped() {

		}

		@Override
		public void onOpponentBeganThinking() {

		}

		@Override
		public void onOpponentStoppedThinking() {

		}

		@Override
		public void onOpponentDisconnected(String opponentName) {

		}

		@Override
		public void onMoveMade(Move m) {

		}

		@Override
		public void onMoveRemade(Move m) {

		}

		@Override
		public void onMoveUnmade(Move m) {

		}

		@Override
		public void onGameOver(Game.Status endState) {

		}

		@Override
		public void setStatusString(String str) {

		}

		@Override
		public void onThinkingInfoChanged(ThinkingInfo thinkingInfo) {

		}

		@Override
		public void requestPromotePiece() {

		}

		@Override
		public void onInvalidMoveRejected(Move m) {

		}

		@Override
		public void setRemainingTime(long wTime, long bTime, long nextUpdate) {

		}

		@Override
		public void onWaitingForOpponent() {

		}

		@Override
		public void onConnectedToOpponent(CharSequence hint) {

		}

		@Override
		public void onOpponentOfferDraw(Move m) {

		}

		@Override
		public void showMessage(CharSequence message, int duration) {

		}

		@Override
		public void showToast(CharSequence message, int duration) {

		}

		@Override
		public void dismissMessage() {

		}

		@Override
		public void onAnalysisInterrupted() {

		}
	};

	public final void setGui(@Nullable GUIInterface guiInterface) {
		if (guiInterface != null) {
			mGuiInterface = new WeakReference<>(guiInterface);
		} else {
			mGuiInterface = null;
		}
	}

	public final boolean hasGame() {
		return getGameMode() != null && getGame() != null;
	}

	@Override
	public boolean isGameActive() {
		return getGameMode() != GameMode.ANALYSIS && getGame() != null
				&& getGame().getGameStatus() == Game.Status.ALIVE;
	}

	public @Nullable abstract Game getGame();

	/**
	 * Move a piece from one square to another.
	 *
	 * @return True if the move was legal, false otherwise.
	 */
	protected boolean doMove(Move move) {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		Log.d(getClass().getSimpleName(), "Controller: playing move " + move);

		Position pos = getGame().currPos();
		Set<Move> moves = MoveGenerator.INSTANCE.generateLegalMoves(pos);
		int promoteTo = move.promoteTo;
		for (Move m : moves) {
			if ((m.from == move.from) && (m.to == move.to)) {
				if ((m.promoteTo != Piece.EMPTY) && (promoteTo == Piece.EMPTY)) {
					promoteMove = m;
					getGui().requestPromotePiece();
					return false;
				}

				if (m.promoteTo == promoteTo) {
					getGame().performMove(m);
					return true;
				}
			}
		}

		return false;
	}

	protected PGNOptions getPGNOptions() {
		if (getGameTextListener() == null) {
			return null;
		} else {
			return getGameTextListener().getPGNOptions();
		}
	}

	@Override
	public final byte[] getPersistableGameState() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		return getGame().getTree().toByteArray();
	}

	/** Convert current game to PGN format. */
	public final String getPGN() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		return getGame().getTree().toPGN(getPGNOptions());
	}

	/** True if human's turn to make a move. (True in analysis mode.) */
	public final boolean isPlayerTurn() {
		return getGame() != null && getGameMode() != null
					&& getGameMode().isPlayerTurn(getGame());
	}

	/** Return true if computer player is using CPU power. */
	public boolean isOpponentThinking() {
		return getGame() != null && getGame().getGameStatus() == Game.Status.ALIVE
				&& (getGameMode() == GameMode.ANALYSIS || !isPlayerTurn());
	}

	protected void updateMoveList() {
		if (getGameTextListener() == null) {
			return;
		}

		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (!getGameTextListener().isUpToDate()) {
			PGNOptions tmpOptions = new PGNOptions();
			tmpOptions.exp.variations = getPGNOptions().view.variations;
			tmpOptions.exp.comments = getPGNOptions().view.comments;
			tmpOptions.exp.nag = getPGNOptions().view.nag;
			tmpOptions.exp.playerAction = false;
			tmpOptions.exp.clockInfo = false;
			tmpOptions.exp.moveNrAfterNag = false;
			getGameTextListener().clear();
			getGame().getTree().pgnTreeWalker(tmpOptions, getGameTextListener());
		}

		getGameTextListener().setCurrent(getGame().getTree().currentNode);
	}

	@Deprecated
	public abstract String getStatusText();

	protected void onMoveMade() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		getGui().setStatusString(getStatusText());

		if (getGameMode() == GameMode.ANALYSIS) {
			onPositionChanged();
		} else {
			updateMoveList();

			if (getGame().getGameStatus() != Game.Status.ALIVE) {
				getGui().onGameOver(getGame().getGameStatus());
			} else {
				Move lastMove = getGame().getLastMove();
				if (lastMove != null) {
					getGui().onMoveMade(getGame().getLastMove());
				}
			}
		}
	}

	protected @Deprecated void onPositionChanged() {
		if (getGameMode() != GameMode.ANALYSIS) {
			return;
		}

		updateMoveList();

		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		getGui().setStatusString(getStatusText());

		StringBuilder sb = new StringBuilder();
		if (getGame().getTree().currentNode != getGame().getTree().rootNode) {
			getGame().getTree().goBack();
			Position pos = getGame().currPos();
			List<Move> prevVarList = getGame().getTree().variations();
			for (int i = 0; i < prevVarList.size(); i++) {
				if (i > 0) {
					sb.append(' ');
				}

				if (i == getGame().getTree().currentNode.defaultChild) {
					sb.append("<b>");
				}

				sb.append(TextIO.moveToString(pos, prevVarList.get(i), false));
				if (i == getGame().getTree().currentNode.defaultChild) {
					sb.append("</b>");
				}
			}
			getGame().getTree().goForward(-1);
		}

		getGui().onPositionChanged(getGame().currPos(), sb.toString(), getGame().getTree().variations());
	}


	private Move promoteMove;

	protected Move getPromoteMove() {
		return promoteMove;
	}

	public final void setPromotionChoice(PromotionPiece promotionPiece) {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		final int choice = promotionPiece.ordinal();

		final boolean white = getGame().currPos().whiteMove;
		int promoteTo;
		switch (choice) {
			case 1:
				promoteTo = white ? Piece.WROOK : Piece.BROOK;
				break;
			case 2:
				promoteTo = white ? Piece.WBISHOP : Piece.BBISHOP;
				break;
			case 3:
				promoteTo = white ? Piece.WKNIGHT : Piece.BKNIGHT;
				break;
			default:
				promoteTo = white ? Piece.WQUEEN : Piece.BQUEEN;
				break;
		}

		promoteMove = new Move(promoteMove.from, promoteMove.to, promoteTo);
		Move m = promoteMove;
		promoteMove = null;
		tryPlayMove(m);
	}

	/**
	 * Help human to claim a draw by trying to find and execute a valid draw
	 * claim.
	 */
	public final boolean claimDrawIfPossible() {
		if (!findValidDrawClaim()) {
			return false;
		} else {
			onMoveMade();   // consider a draw to be a "move"
			return true;
		}
	}

	private boolean findValidDrawClaim() {
		if (getGame() == null) {
			throw new IllegalStateException("Game is not initialized");
		}

		if (getGame().getGameStatus() != Game.Status.ALIVE) {
			return true;
		}

		getGame().processString("draw accept");
		if (getGame().getGameStatus() != Game.Status.ALIVE) {
			return true;
		}

		getGame().processString("draw rep");
		if (getGame().getGameStatus() != Game.Status.ALIVE) {
			return true;
		}

		getGame().processString("draw 50");
		return getGame().getGameStatus() != Game.Status.ALIVE;
	}


	@Override
	public boolean canUndoMove() {
		return getGame() != null && getGame().getLastMove() != null;
	}

	@Override
	public boolean canRedoMove() {
		return getGame() != null && getGame().canRedoMove();
	}

	public final void stopGame() {
		stopGame(true);
	}
}
