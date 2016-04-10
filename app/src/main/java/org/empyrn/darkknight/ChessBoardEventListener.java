package org.empyrn.darkknight;

import android.view.MotionEvent;
import android.view.View;

import org.empyrn.darkknight.gamelogic.GameController;
import org.empyrn.darkknight.gamelogic.Move;

/**
 * Created by nick on 1/30/16.
 *
 * Bridge between the chess board view and the controller.
 */
public class ChessBoardEventListener implements View.OnTouchListener {
	private final GameController mGameController;

	public ChessBoardEventListener(GameController gameController) {
		mGameController = gameController;
	}

	public boolean onTouch(View v, MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_UP) {
			handleClick((ChessBoardView) v, event);
		}

		return true;
	}

	private void handleClick(ChessBoardView mChessBoardView, MotionEvent e) {
		if (!mGameController.isPlayerTurn()) {
			return;
		}

		int sq = mChessBoardView.eventToSquare(e);
		Move m = mChessBoardView.mousePressed(sq);
		if (m != null) {
			mGameController.tryPlayMove(m);
		}
	}
}
