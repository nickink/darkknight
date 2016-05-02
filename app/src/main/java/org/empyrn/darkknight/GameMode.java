package org.empyrn.darkknight;

import android.support.annotation.NonNull;

import org.empyrn.darkknight.gamelogic.Game;

public enum GameMode {

	PLAYER_WHITE,
	PLAYER_BLACK,
	TWO_PLAYERS,
	ANALYSIS,
	TWO_COMPUTERS;

	@Deprecated
	public final boolean playerWhite() {
		return this == PLAYER_WHITE || this == TWO_PLAYERS;
	}

	@Deprecated
	public final boolean playerBlack() {
		return this == PLAYER_BLACK || this == TWO_PLAYERS;
	}

	public final boolean isPlayerTurn(@NonNull Game game) {
		return isPlayerTurn(game.currPos().whiteMove);
	}

	private boolean isPlayerTurn(boolean whiteMove) {
		return (whiteMove ? playerWhite() : playerBlack()) || (this == ANALYSIS);
	}
}
