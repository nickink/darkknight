package org.empyrn.darkknight.gamelogic;

import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.empyrn.darkknight.GUIInterface;
import org.empyrn.darkknight.GameMode;

/**
 * Created by nick on 10/3/15.
 */
public interface GameController {
	@Nullable GUIInterface getGui();
	void setGui(@Nullable GUIInterface guiInterface);

	@Nullable PgnToken.PgnTokenReceiver getGameTextListener();
	void setGameTextListener(@Nullable PgnToken.PgnTokenReceiver pgnTokenReceiver);

	@Nullable Game getGame();

	boolean hasGame();
	boolean isGameActive();

	byte[] getPersistableGameState();
	void restoreGame(GameMode gameMode, byte[] state);

	/**
	 * Start a new game.
	 */
	void startGame();

	/**
	 * Resume the game.
	 */
	void resumeGame();

	/**
	 * Pause the game. Effectively means the game is still alive but the clock isn't running, although
	 * this isn't implemented presently.
	 */
	void pauseGame();

	/**
	 * End the game.
	 */
	void stopGame();

	String getPGN();

	@Nullable GameMode getGameMode();
	void setGameMode(GameMode gameMode);

	void tryPlayMove(Move m);
	void setPromotionChoice(PromotionPiece choice);

	boolean claimDrawIfPossible();
	void acceptDrawOffer();
	void declineDrawOffer();
	void resignGame();

	boolean isPlayerTurn();
	boolean isOpponentThinking();

	boolean canUndoMove();
	boolean canRedoMove();

	boolean isAnalyzing();

	String getStatusText();
}
