package org.empyrn.darkknight.gamelogic;

import java.io.Serializable;

/**
 * Contains enough information to undo a previous move.
 * Set by makeMove(). Used by unMakeMove().
 * @author petero
 */
public final class UndoInfo implements Serializable {
    int capturedPiece;
    int castleMask;
    int epSquare;
    int halfMoveClock;
}
