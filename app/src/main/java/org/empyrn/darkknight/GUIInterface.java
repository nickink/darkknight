package org.empyrn.darkknight;

import org.empyrn.darkknight.engine.ThinkingInfo;
import org.empyrn.darkknight.gamelogic.Game;
import org.empyrn.darkknight.gamelogic.Move;
import org.empyrn.darkknight.gamelogic.Position;

import java.util.List;

import android.os.Handler;
import android.support.annotation.NonNull;


/**
 * Interface between the GUI and the EngineController.
 */
public interface GUIInterface {

	/**
	 * Update the displayed board position.
	 */
	@Deprecated
	void onPositionChanged(Position newPosition, String variantInfo, List<Move> variantMoves);

	void onNewGameStarted();

	void onGameRestored();

	void onGameResumed();

	void onGamePaused();

	void onGameStopped();

	void onOpponentBeganThinking();
	void onOpponentStoppedThinking();

	/**
	 * Called when a move was made. This is called even if the GUI initiated the move.
	 */
	void onMoveMade(Move m);

	void onMoveRemade(Move m);
	void onMoveUnmade(Move m);

	void onGameOver(Game.Status endState);

	/**
	 * Set the status text.
	 */
	@Deprecated
	void setStatusString(String str);

	/**
	 * Update the computer thinking information.
	 */
	void onThinkingInfoChanged(ThinkingInfo thinkingInfo);

	/**
	 * Ask what to promote a pawn to. Should call setPromotionChoice() when done.
	 */
	void requestPromotePiece();

	/**
	 * Report that user attempted to make an invalid move.
	 */
	void onInvalidMoveRejected(Move m);

	/**
	 * Report remaining thinking time to GUI.
	 */
	void setRemainingTime(long wTime, long bTime, long nextUpdate);

	void onWaitingForOpponent();

	void onConnectedToOpponent(CharSequence hint);

	void onOpponentOfferDraw(Move m);


	@Deprecated
	void showMessage(CharSequence message, int duration);

	@Deprecated
	void dismissMessage();
}
