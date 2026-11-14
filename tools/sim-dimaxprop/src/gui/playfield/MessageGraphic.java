/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui.playfield;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;

import core.Coord;
import core.DTNHost;
import core.Message;

/**
 * Visualization of an active message transfer between two hosts.
 */
public class MessageGraphic extends PlayFieldGraphic {

	private static final Color COLOR_BEAM = new Color(231, 76, 60);       // Vibrant Red/Coral
	private static final Color COLOR_GLOW = new Color(231, 76, 60, 80);
	private static final Color COLOR_BADGE_BG = new Color(30, 39, 46, 230);
	private static final Font FONT_MSG_TAG = new Font("SansSerif", Font.BOLD, 10);

	private DTNHost from;
	private DTNHost to;
	private Message message;

	public MessageGraphic(DTNHost from, DTNHost to) {
		this(from, to, null);
	}

	public MessageGraphic(DTNHost from, DTNHost to, Message message) {
		this.from = from;
		this.to = to;
		this.message = message;
	}

	@Override
	public void draw(Graphics2D g2) {
		if (from == null || to == null) return;

		Coord fromLoc = from.getLocation();
		Coord toLoc = to.getLocation();
		if (fromLoc == null || toLoc == null) return;

		Object origAntialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int x1 = scale(fromLoc.getX());
		int y1 = scale(fromLoc.getY());
		int x2 = scale(toLoc.getX());
		int y2 = scale(toLoc.getY());

		// 1. Draw outer glowing transfer beam
		g2.setStroke(new BasicStroke(5.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.setColor(COLOR_GLOW);
		g2.drawLine(x1, y1, x2, y2);

		// 2. Draw active transmission line
		float[] dash = { 6.0f, 3.0f };
		g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dash, 0.0f));
		g2.setColor(COLOR_BEAM);
		g2.drawLine(x1, y1, x2, y2);

		// 3. Draw arrowhead pointing to receiving node
		drawArrowHead(g2, x1, y1, x2, y2, COLOR_BEAM);

		// 4. Receiving node ripple halo
		g2.setColor(new Color(231, 76, 60, 70));
		g2.fill(new Ellipse2D.Double(x2 - 12, y2 - 12, 24, 24));
		g2.setColor(COLOR_BEAM);
		g2.setStroke(new BasicStroke(1.5f));
		g2.draw(new Ellipse2D.Double(x2 - 8, y2 - 8, 16, 16));

		// 5. Draw message identifier tag if available
		int midX = (x1 + x2) / 2;
		int midY = (y1 + y2) / 2;
		String tagText = (message != null) ? ("✉ " + message.getId()) : "✉ transfer";
		drawTag(g2, midX, midY, tagText);

		// Restore stroke & hints
		g2.setStroke(new BasicStroke(1.0f));
		if (origAntialias != null) {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, origAntialias);
		}
	}

	private void drawArrowHead(Graphics2D g2, int x1, int y1, int x2, int y2, Color color) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 8) return;

		double offset = 8.0;
		double tipX = x2 - (dx / len) * offset;
		double tipY = y2 - (dy / len) * offset;
		double angle = Math.atan2(dy, dx);
		int arrowSize = 9;

		AffineTransform orig = g2.getTransform();
		AffineTransform at = new AffineTransform(orig);
		at.translate(tipX, tipY);
		at.rotate(angle);
		g2.setTransform(at);

		g2.setColor(color);
		int[] xPoints = { 0, -arrowSize, -arrowSize };
		int[] yPoints = { 0, -arrowSize / 2, arrowSize / 2 };
		g2.fillPolygon(xPoints, yPoints, 3);

		g2.setTransform(orig);
	}

	private void drawTag(Graphics2D g2, int cx, int cy, String text) {
		g2.setFont(FONT_MSG_TAG);
		FontMetrics fm = g2.getFontMetrics();
		int padX = 5;
		int padY = 2;
		int w = fm.stringWidth(text) + padX * 2;
		int h = fm.getHeight() + padY * 2;
		int x = cx - w / 2;
		int y = cy - h / 2;

		g2.setColor(COLOR_BADGE_BG);
		g2.fillRoundRect(x, y, w, h, 6, 6);
		g2.setColor(COLOR_BEAM);
		g2.setStroke(new BasicStroke(1.2f));
		g2.drawRoundRect(x, y, w, h, 6, 6);

		g2.setColor(Color.WHITE);
		g2.drawString(text, x + padX, y + fm.getAscent() + padY);
	}
}
