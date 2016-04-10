package org.empyrn.darkknight;

import java.io.Serializable;

/** Settings controlling PGN import/export */
public final class PGNOptions implements Serializable {

	public static class Viewer implements Serializable {
		public boolean variations;
		public boolean comments;
		public boolean nag;
		public boolean headers;
	}

	public static final class Import implements Serializable {
		public boolean variations;
		public boolean comments;
		public boolean nag;
	}

	public static final class Export implements Serializable {
		public boolean variations;
		public boolean comments;
		public boolean nag;
		public boolean playerAction;
		public boolean clockInfo;
		public boolean pgnPromotions;
		public boolean moveNrAfterNag;
	}

	public Viewer view;
	public Import imp;
	public Export exp;

	public PGNOptions() {
		view = new Viewer();
		imp = new Import();
		exp = new Export();
		exp.moveNrAfterNag = true;
	}
}
