package org.empyrn.darkknight;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;

import org.empyrn.darkknight.gamelogic.GameTree;
import org.empyrn.darkknight.gamelogic.PgnToken;

import java.util.HashMap;

/**
 * Created by nick on 1/17/16.
 * <p/>
 * Renders PGN data for display.
 */
public class PGNScreenText implements PgnToken.PgnTokenReceiver {
	private SpannableStringBuilder sb = new SpannableStringBuilder();
	private int prevType = PgnToken.EOF;
	private int nestLevel = 0;
	private boolean col0 = true;
	private int currPos = 0, endPos = 0;
	private boolean upToDate = false;
	private final PGNOptions options;

	private static class NodeInfo {
		int l0, l1;

		NodeInfo(int ls, int le) {
			l0 = ls;
			l1 = le;
		}
	}

	private HashMap<GameTree.Node, NodeInfo> nodeToCharPos;

	public PGNScreenText(SharedPreferences settings, PGNOptions options) {
		this.options = options;

		options.view.variations = settings
				.getBoolean("viewVariations", true);
		options.view.comments = settings.getBoolean("viewComments", true);
		options.view.nag = settings.getBoolean("viewNAG", true);
		options.view.headers = settings.getBoolean("viewHeaders", false);
		options.imp.variations = settings.getBoolean("importVariations",
				true);
		options.imp.comments = settings.getBoolean("importComments", true);
		options.imp.nag = settings.getBoolean("importNAG", true);
		options.exp.variations = settings.getBoolean("exportVariations",
				true);
		options.exp.comments = settings.getBoolean("exportComments", true);
		options.exp.nag = settings.getBoolean("exportNAG", true);
		options.exp.playerAction = settings.getBoolean("exportPlayerAction",
				false);
		options.exp.clockInfo = settings.getBoolean("exportTime", false);

		nodeToCharPos = new HashMap<>();
	}

	public final SpannableStringBuilder getSpannableData() {
		return sb;
	}

	public final boolean atEnd() {
		return currPos >= endPos - 10;
	}

	public boolean isUpToDate() {
		return upToDate;
	}

	int paraStart = 0;
	int paraIndent = 0;
	boolean paraBold = false;

	private void newLine() {
		if (!col0) {
			if (paraIndent > 0) {
				int paraEnd = sb.length();
				int indentStep = 15;
				int indent = paraIndent * indentStep;
				sb.setSpan(new LeadingMarginSpan.Standard(indent),
						paraStart, paraEnd,
						Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			if (paraBold) {
				int paraEnd = sb.length();
				sb.setSpan(new StyleSpan(Typeface.BOLD), paraStart,
						paraEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
			sb.append('\n');
			paraStart = sb.length();
			paraIndent = nestLevel;
			paraBold = false;
		}
		col0 = true;
	}

	boolean pendingNewLine = false;

	public void processToken(GameTree.Node node, int type, String token) {
		if ((prevType == PgnToken.RIGHT_BRACKET)
				&& (type != PgnToken.LEFT_BRACKET)) {
			if (options.view.headers) {
				col0 = false;
				newLine();
			} else {
				sb.clear();
				paraBold = false;
			}
		}
		if (pendingNewLine) {
			if (type != PgnToken.RIGHT_PAREN) {
				newLine();
				pendingNewLine = false;
			}
		}
		switch (type) {
			case PgnToken.STRING:
				sb.append(" \"");
				sb.append(token);
				sb.append('"');
				break;
			case PgnToken.INTEGER:
				if ((prevType != PgnToken.LEFT_PAREN)
						&& (prevType != PgnToken.RIGHT_BRACKET) && !col0)
					sb.append(' ');
				sb.append(token);
				col0 = false;
				break;
			case PgnToken.PERIOD:
				sb.append('.');
				col0 = false;
				break;
			case PgnToken.ASTERISK:
				sb.append(" *");
				col0 = false;
				break;
			case PgnToken.LEFT_BRACKET:
				sb.append('[');
				col0 = false;
				break;
			case PgnToken.RIGHT_BRACKET:
				sb.append("]\n");
				col0 = false;
				break;
			case PgnToken.LEFT_PAREN:
				nestLevel++;
				if (col0)
					paraIndent++;
				newLine();
				sb.append('(');
				col0 = false;
				break;
			case PgnToken.RIGHT_PAREN:
				sb.append(')');
				nestLevel--;
				pendingNewLine = true;
				break;
			case PgnToken.NAG:
				sb.append(GameTree.Node.nagStr(Integer.parseInt(token)));
				col0 = false;
				break;
			case PgnToken.SYMBOL: {
				if ((prevType != PgnToken.RIGHT_BRACKET)
						&& (prevType != PgnToken.LEFT_BRACKET) && !col0)
					sb.append(' ');
				int l0 = sb.length();
				sb.append(token);
				int l1 = sb.length();
				nodeToCharPos.put(node, new NodeInfo(l0, l1));
				if (endPos < l0)
					endPos = l0;
				col0 = false;
				if (nestLevel == 0)
					paraBold = true;
				break;
			}
			case PgnToken.COMMENT:
				if (prevType == PgnToken.RIGHT_BRACKET) {
				} else if (nestLevel == 0) {
					nestLevel++;
					newLine();
					nestLevel--;
				} else {
					if ((prevType != PgnToken.LEFT_PAREN) && !col0) {
						sb.append(' ');
					}
				}
				sb.append(token.replaceAll("[ \t\r\n]+", " ").trim());
				col0 = false;
				if (nestLevel == 0)
					newLine();
				break;
			case PgnToken.EOF:
				newLine();
				upToDate = true;
				break;
		}

		prevType = type;
	}

	@Override
	public void clear() {
		sb.clear();
		prevType = PgnToken.EOF;
		nestLevel = 0;
		col0 = true;
		currPos = 0;
		endPos = 0;
		nodeToCharPos.clear();
		paraStart = 0;
		paraIndent = 0;
		paraBold = false;
		pendingNewLine = false;

		upToDate = false;
	}

	BackgroundColorSpan bgSpan = new BackgroundColorSpan(0xff888888);

	@Override
	public void setCurrent(GameTree.Node node) {
		sb.removeSpan(bgSpan);
		NodeInfo ni = nodeToCharPos.get(node);
		if (ni != null) {
			sb.setSpan(bgSpan, ni.l0, ni.l1,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			currPos = ni.l0;
		}
	}

	@Override
	public PGNOptions getPGNOptions() {
		return options;
	}
}
