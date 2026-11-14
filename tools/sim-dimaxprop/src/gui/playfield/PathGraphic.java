/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui.playfield;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

import movement.Path;
import core.Coord;

/**
 * Visualization of a movement Path.
 */
public class PathGraphic extends PlayFieldGraphic {
	private static final Color PATH_COLOR = new Color(231, 76, 60);       // Red/Coral
	private static final Color PATH_GLOW = new Color(231, 76, 60, 60);
	private static final Color WAYPOINT_COLOR = new Color(192, 57, 43);

	private List<Coord> coords;

	public PathGraphic(Path path) {
		if (path == null) {
			this.coords = null;
		}
		else {
			this.coords = path.getCoords();
		}
	}

	/**
	 * Draws a line through all path's coordinates with anti-aliasing and waypoint dots.
	 * @param g2 The graphics context to draw to
	 */
	@Override
	public void draw(Graphics2D g2) {
		if (coords == null || coords.size() < 2) {
			return;
		}

		Object origAntialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Outer glow
		g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.setColor(PATH_GLOW);
		Coord prev = coords.get(0);
		for (int i = 1, n = coords.size(); i < n; i++) {
			Coord next = coords.get(i);
			g2.drawLine(scale(prev.getX()), scale(prev.getY()),
					scale(next.getX()), scale(next.getY()));
			prev = next;
		}

		// Core path line
		float[] dash = { 6.0f, 3.0f };
		g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dash, 0.0f));
		g2.setColor(PATH_COLOR);
		prev = coords.get(0);
		for (int i = 1, n = coords.size(); i < n; i++) {
			Coord next = coords.get(i);
			g2.drawLine(scale(prev.getX()), scale(prev.getY()),
					scale(next.getX()), scale(next.getY()));
			prev = next;
		}

		// Waypoint dots
		g2.setColor(WAYPOINT_COLOR);
		for (Coord c : coords) {
			int cx = scale(c.getX());
			int cy = scale(c.getY());
			g2.fillOval(cx - 3, cy - 3, 6, 6);
		}

		// Restore stroke & hints
		g2.setStroke(new BasicStroke(1.0f));
		if (origAntialias != null) {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, origAntialias);
		}
	}

}
