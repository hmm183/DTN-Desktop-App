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
import java.util.ArrayList;
import java.util.List;

import core.Coord;
import core.DTNHost;
import core.Message;

/**
 * Visualization of a complete message hop path on the playfield.
 * Draws high-visibility directed arrows, hop numbers, node halos,
 * and path metadata.
 */
public class MessagePathGraphic extends PlayFieldGraphic {

	private static final Color COLOR_SRC = new Color(46, 204, 113);       // Emerald Green
	private static final Color COLOR_RELAY = new Color(243, 156, 18);     // Amber
	private static final Color COLOR_DEST = new Color(155, 89, 182);      // Purple
	private static final Color COLOR_ARROW_LINE = new Color(41, 128, 185);// Deep Blue
	private static final Color COLOR_ARROW_GLOW = new Color(52, 152, 219, 90);
	private static final Color COLOR_BADGE_BG = new Color(44, 62, 80, 220);

	private static final Font FONT_HOP_BADGE = new Font("SansSerif", Font.BOLD, 11);
	private static final Font FONT_NODE_TAG = new Font("SansSerif", Font.BOLD, 10);
	private static final Font FONT_SUMMARY = new Font("SansSerif", Font.PLAIN, 11);

	private Message message;
	private List<DTNHost> hops;
	private List<Coord> cachedCoords;

	public MessagePathGraphic(Message message) {
		this.message = message;
		this.hops = new ArrayList<DTNHost>(message.getHops());
		this.cachedCoords = new ArrayList<Coord>();
		for (DTNHost h : this.hops) {
			cachedCoords.add(h.getLocation().clone());
		}
	}

	public Message getMessage() {
		return this.message;
	}

	@Override
	public void draw(Graphics2D g2) {
		if (hops == null || hops.isEmpty()) {
			return;
		}

		Object origAntialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		List<Coord> currentCoords = new ArrayList<Coord>();
		for (int i = 0; i < hops.size(); i++) {
			DTNHost h = hops.get(i);
			Coord c = (h != null && h.getLocation() != null) ? h.getLocation() : cachedCoords.get(i);
			currentCoords.add(c);
		}

		// 1. Draw hop connection lines and directional arrows
		for (int i = 0; i < currentCoords.size() - 1; i++) {
			Coord from = currentCoords.get(i);
			Coord to = currentCoords.get(i + 1);

			int x1 = scale(from.getX());
			int y1 = scale(from.getY());
			int x2 = scale(to.getX());
			int y2 = scale(to.getY());

			// Outer glow line
			g2.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(COLOR_ARROW_GLOW);
			g2.drawLine(x1, y1, x2, y2);

			// Main dashed arrow line
			float[] dashPattern = { 8.0f, 4.0f };
			g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10.0f, dashPattern, 0.0f));
			g2.setColor(COLOR_ARROW_LINE);
			g2.drawLine(x1, y1, x2, y2);

			// Arrowhead
			drawArrowHead(g2, x1, y1, x2, y2, COLOR_ARROW_LINE);

			// Hop number badge in the middle
			int midX = (x1 + x2) / 2;
			int midY = (y1 + y2) / 2;
			drawHopBadge(g2, midX, midY, i + 1);
		}

		// 2. Draw node halos and role badges (SRC, RELAY, DEST)
		for (int i = 0; i < currentCoords.size(); i++) {
			Coord c = currentCoords.get(i);
			DTNHost h = hops.get(i);
			int x = scale(c.getX());
			int y = scale(c.getY());

			Color roleColor;
			String roleLabel;

			if (i == 0) {
				roleColor = COLOR_SRC;
				roleLabel = "SRC: " + h.toString();
			} else if (i == currentCoords.size() - 1) {
				roleColor = COLOR_DEST;
				roleLabel = "DEST: " + h.toString();
			} else {
				roleColor = COLOR_RELAY;
				roleLabel = "HOP " + i + ": " + h.toString();
			}

			// Outer pulse halo
			g2.setColor(new Color(roleColor.getRed(), roleColor.getGreen(), roleColor.getBlue(), 60));
			g2.fill(new Ellipse2D.Double(x - 16, y - 16, 32, 32));

			// Inner colored ring
			g2.setColor(roleColor);
			g2.setStroke(new BasicStroke(2.5f));
			g2.draw(new Ellipse2D.Double(x - 10, y - 10, 20, 20));

			// Center dot
			g2.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));

			// Role text pill
			drawNodeTag(g2, x, y + 16, roleLabel, roleColor);
		}

		// 3. Draw Message Overview Card on Playfield
		if (!currentCoords.isEmpty()) {
			Coord first = currentCoords.get(0);
			drawSummaryCard(g2, scale(first.getX()) - 20, scale(first.getY()) - 45);
		}

		g2.setStroke(new BasicStroke(1.0f));
		if (origAntialias != null) {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, origAntialias);
		}
	}

	private void drawArrowHead(Graphics2D g2, int x1, int y1, int x2, int y2, Color color) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 10) return;

		double offset = 12.0;
		double tipX = x2 - (dx / len) * offset;
		double tipY = y2 - (dy / len) * offset;

		double angle = Math.atan2(dy, dx);
		int arrowSize = 10;

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

	private void drawHopBadge(Graphics2D g2, int cx, int cy, int hopNumber) {
		String text = String.valueOf(hopNumber);
		g2.setFont(FONT_HOP_BADGE);
		FontMetrics fm = g2.getFontMetrics();
		int r = 10;

		g2.setColor(COLOR_BADGE_BG);
		g2.fillOval(cx - r, cy - r, r * 2, r * 2);
		g2.setColor(Color.WHITE);
		g2.setStroke(new BasicStroke(1.5f));
		g2.drawOval(cx - r, cy - r, r * 2, r * 2);

		int tx = cx - fm.stringWidth(text) / 2;
		int ty = cy + fm.getAscent() / 2 - 2;
		g2.drawString(text, tx, ty);
	}

	private void drawNodeTag(Graphics2D g2, int cx, int topY, String text, Color accent) {
		g2.setFont(FONT_NODE_TAG);
		FontMetrics fm = g2.getFontMetrics();
		int textWidth = fm.stringWidth(text);
		int padX = 5;
		int padY = 2;
		int w = textWidth + padX * 2;
		int h = fm.getHeight() + padY * 2;
		int x = cx - w / 2;
		int y = topY;

		g2.setColor(COLOR_BADGE_BG);
		g2.fillRoundRect(x, y, w, h, 6, 6);
		g2.setColor(accent);
		g2.setStroke(new BasicStroke(1.5f));
		g2.drawRoundRect(x, y, w, h, 6, 6);

		g2.setColor(Color.WHITE);
		g2.drawString(text, x + padX, y + fm.getAscent() + padY);
	}

	private void drawSummaryCard(Graphics2D g2, int x, int y) {
		String line1 = "Msg " + message.getId() + " (" + (message.getHops().size() - 1) + " hops)";
		String line2 = message.getFrom() + " -> " + message.getTo() + " [" + (message.getSize() / 1024) + " KB]";

		g2.setFont(FONT_SUMMARY);
		FontMetrics fm = g2.getFontMetrics();
		int w = Math.max(fm.stringWidth(line1), fm.stringWidth(line2)) + 14;
		int h = fm.getHeight() * 2 + 8;

		g2.setColor(new Color(24, 32, 42, 230));
		g2.fillRoundRect(x, y, w, h, 8, 8);
		g2.setColor(COLOR_ARROW_LINE);
		g2.setStroke(new BasicStroke(1.5f));
		g2.drawRoundRect(x, y, w, h, 8, 8);

		g2.setColor(new Color(241, 196, 15));
		g2.drawString(line1, x + 7, y + fm.getAscent() + 3);
		g2.setColor(Color.WHITE);
		g2.drawString(line2, x + 7, y + fm.getAscent() * 2 + 5);
	}
}
