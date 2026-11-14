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
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;

import core.Connection;
import core.Coord;
import core.DTNHost;
import core.NetworkInterface;

/**
 * Modernized Visualization of a DTN Node with anti-aliasing,
 * translucent radio coverage, group-aware styling, buffer indicator,
 * and high-contrast readable name tags.
 */
public class NodeGraphic extends PlayFieldGraphic {
	private static boolean drawCoverage = true;
	private static boolean drawNodeName = true;
	private static boolean drawConnections = true;
	private static boolean drawBuffer = true;
	private static List<DTNHost> highlightedNodes;

	// Palette
	private static final Color RANGE_FILL = new Color(46, 204, 113, 20);       // Translucent Green
	private static final Color RANGE_STROKE = new Color(39, 174, 96, 130);     // Green outline
	private static final Color CON_COLOR = new Color(44, 62, 80, 180);          // Charcoal/Navy
	private static final Color CON_ACTIVE_COLOR = new Color(41, 128, 185, 220); // Blue for active transfer

	// Node group colors
	private static final Color NODE_PEDESTRIAN = new Color(41, 128, 185);      // Blue
	private static final Color NODE_CAR = new Color(230, 126, 34);             // Orange
	private static final Color NODE_TRAM = new Color(142, 68, 173);            // Purple
	private static final Color NODE_DEFAULT = new Color(52, 73, 94);           // Slate

	private static final Color HIGHLIGHT_HALO = new Color(233, 30, 99, 80);
	private static final Color HIGHLIGHT_RING = new Color(233, 30, 99);

	private static final Color TAG_BG = new Color(24, 32, 42, 210);
	private static final Color BUFFER_BG = new Color(44, 62, 80, 180);
	private static final Color BUFFER_FG = new Color(46, 204, 113);
	private static final Color BUFFER_FULL_FG = new Color(231, 76, 60);

	private static final Font FONT_NODE_NAME = new Font("SansSerif", Font.BOLD, 10);
	private static final Font FONT_BUFFER = new Font("SansSerif", Font.PLAIN, 9);

	private DTNHost node;

	public NodeGraphic(DTNHost node) {
		this.node = node;
	}

	@Override
	public void draw(Graphics2D g2) {
		Object origAntialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		drawHost(g2);
		if (drawBuffer) {
			drawMessages(g2);
		}

		if (origAntialias != null) {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, origAntialias);
		}
	}

	private boolean isHighlighted() {
		if (highlightedNodes == null) {
			return false;
		} else {
			return highlightedNodes.contains(node);
		}
	}

	private Color getNodeColor() {
		String name = node.toString().toLowerCase();
		if (name.startsWith("p")) {
			return NODE_PEDESTRIAN;
		} else if (name.startsWith("c")) {
			return NODE_CAR;
		} else if (name.startsWith("t") || name.startsWith("b")) {
			return NODE_TRAM;
		}
		return NODE_DEFAULT;
	}

	/**
	 * Visualize node's location, radio ranges, connections, and name tag.
	 */
	private void drawHost(Graphics2D g2) {
		Coord loc = node.getLocation();
		if (loc == null) return;

		int cx = scale(loc.getX());
		int cy = scale(loc.getY());

		// 1. Radio Range (Translucent circle + crisp border)
		if (drawCoverage && node.isRadioActive()) {
			ArrayList<NetworkInterface> interfaces = new ArrayList<NetworkInterface>(node.getInterfaces());
			for (NetworkInterface ni : interfaces) {
				double range = ni.getTransmitRange();
				double rScaled = range * getScale();
				Ellipse2D.Double coverage = new Ellipse2D.Double(cx - rScaled, cy - rScaled, rScaled * 2, rScaled * 2);

				g2.setColor(RANGE_FILL);
				g2.fill(coverage);

				g2.setColor(RANGE_STROKE);
				float[] dash = { 4.0f, 4.0f };
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
				g2.draw(coverage);
			}
		}

		// 2. Connections between nodes
		if (drawConnections) {
			ArrayList<Connection> conList = new ArrayList<Connection>(node.getConnections());
			for (Connection c : conList) {
				DTNHost otherNode = c.getOtherNode(node);
				if (otherNode == null || otherNode.getLocation() == null) {
					continue;
				}
				Coord c2 = otherNode.getLocation();
				int x2 = scale(c2.getX());
				int y2 = scale(c2.getY());

				// If connection is actively transferring a message, highlight it
				if (c.isTransferring()) {
					g2.setColor(CON_ACTIVE_COLOR);
					g2.setStroke(new BasicStroke(2.0f));
				} else {
					g2.setColor(CON_COLOR);
					g2.setStroke(new BasicStroke(1.2f));
				}
				g2.drawLine(cx, cy, x2, y2);
			}
		}

		// 3. Highlight halo if selected
		if (isHighlighted()) {
			g2.setColor(HIGHLIGHT_HALO);
			g2.fill(new Ellipse2D.Double(cx - 14, cy - 14, 28, 28));

			g2.setColor(HIGHLIGHT_RING);
			g2.setStroke(new BasicStroke(2.0f));
			g2.draw(new Ellipse2D.Double(cx - 10, cy - 10, 20, 20));
		}

		// 4. Node Glyph (Solid rounded disc with white center core)
		Color hostCol = getNodeColor();
		int nodeRadius = 5;

		// Outer circle
		g2.setColor(Color.WHITE);
		g2.fillOval(cx - nodeRadius - 1, cy - nodeRadius - 1, (nodeRadius + 1) * 2, (nodeRadius + 1) * 2);
		g2.setColor(hostCol);
		g2.fillOval(cx - nodeRadius, cy - nodeRadius, nodeRadius * 2, nodeRadius * 2);
		g2.setColor(Color.WHITE);
		g2.fillOval(cx - 1, cy - 1, 2, 2);

		// 5. Node Name Tag Badge
		if (drawNodeName) {
			drawNameBadge(g2, cx, cy, node.toString(), hostCol);
		}

		g2.setStroke(new BasicStroke(1.0f));
	}

	private void drawNameBadge(Graphics2D g2, int cx, int cy, String name, Color accent) {
		g2.setFont(FONT_NODE_NAME);
		FontMetrics fm = g2.getFontMetrics();
		int padX = 4;
		int padY = 1;
		int w = fm.stringWidth(name) + padX * 2;
		int h = fm.getHeight() + padY * 2;
		int x = cx + 7;
		int y = cy - h / 2;

		// Pill background
		g2.setColor(TAG_BG);
		g2.fillRoundRect(x, y, w, h, 5, 5);
		g2.setColor(accent);
		g2.setStroke(new BasicStroke(1.0f));
		g2.drawRoundRect(x, y, w, h, 5, 5);

		// Label text
		g2.setColor(Color.WHITE);
		g2.drawString(name, x + padX, y + fm.getAscent() + padY);
	}

	/**
	 * Sets whether radio coverage of nodes should be drawn
	 */
	public static void setDrawCoverage(boolean draw) {
		drawCoverage = draw;
	}

	/**
	 * Sets whether node's name should be displayed
	 */
	public static void setDrawNodeName(boolean draw) {
		drawNodeName = draw;
	}

	/**
	 * Sets whether node's connections to other nodes should be drawn
	 */
	public static void setDrawConnections(boolean draw) {
		drawConnections = draw;
	}

	/**
	 * Sets whether node's message buffer is shown
	 */
	public static void setDrawBuffer(boolean draw) {
		drawBuffer = draw;
	}

	public static void setHighlightedNodes(List<DTNHost> nodes) {
		highlightedNodes = nodes;
	}

	/**
	 * Visualize the messages this node is carrying with a sleek mini progress bar.
	 */
	private void drawMessages(Graphics2D g2) {
		int nrofMessages = node.getNrofMessages();
		if (nrofMessages == 0) return;

		Coord loc = node.getLocation();
		if (loc == null) return;
		int cx = scale(loc.getX());
		int cy = scale(loc.getY());

		int barW = 20;
		int barH = 4;
		int barX = cx - barW / 2;
		int barY = cy + 8;

		// Background container
		g2.setColor(BUFFER_BG);
		g2.fillRoundRect(barX, barY, barW, barH, 2, 2);

		// Fill ratio based on message count (up to 10 for full)
		double ratio = Math.min(1.0, nrofMessages / 10.0);
		int fillW = (int)(barW * ratio);

		g2.setColor(ratio > 0.8 ? BUFFER_FULL_FG : BUFFER_FG);
		g2.fillRoundRect(barX, barY, Math.max(2, fillW), barH, 2, 2);

		// Small message count indicator if > 1
		if (nrofMessages > 1) {
			g2.setFont(FONT_BUFFER);
			g2.setColor(Color.WHITE);
			g2.drawString(String.valueOf(nrofMessages), barX + barW + 2, barY + barH);
		}
	}
}
