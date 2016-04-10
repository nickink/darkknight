package org.empyrn.darkknight;

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

	@Deprecated
	public final boolean analysisMode() {
		return this == ANALYSIS;
	}

	public final boolean isPlayerTurn(boolean whiteMove) {
		return (whiteMove ? playerWhite() : playerBlack()) || (this == ANALYSIS);
	}
}
