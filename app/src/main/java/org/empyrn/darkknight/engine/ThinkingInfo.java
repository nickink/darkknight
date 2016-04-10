package org.empyrn.darkknight.engine;

import android.support.annotation.Nullable;

import org.empyrn.darkknight.gamelogic.Move;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Created by nick on 3/6/16.
 */
public class ThinkingInfo implements Serializable {
	public final int pvScore;
	public final String pvStr;
	public final String bookInfo;
	public final List<Move> pvMoves;
	public final List<Move> bookMoves;

	public ThinkingInfo(int pvScore, String pvStr, String bookInfo, @Nullable List<Move> pvMoves,
	                    @Nullable List<Move> bookMoves) {
		this.pvScore = pvScore;
		this.pvStr = pvStr;
		this.bookInfo = bookInfo;
		this.pvMoves = pvMoves == null ? null : Collections.unmodifiableList(pvMoves);
		this.bookMoves = bookMoves == null ? null : Collections.unmodifiableList(bookMoves);
	}

	@Override
	public String toString() {
		return "ThinkingInfo{" +
				"pvStr='" + pvStr + '\'' +
				", bookInfo='" + bookInfo + '\'' +
				", pvMoves=" + pvMoves +
				", bookMoves=" + bookMoves +
				'}';
	}
}
