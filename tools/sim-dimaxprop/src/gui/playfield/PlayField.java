/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui.playfield;

import gui.DTNSimGUI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JPanel;

import movement.Path;
import movement.map.SimMap;
import core.Coord;
import core.DTNHost;
import core.Message;
import core.World;

/**
 * The canvas where node graphics, message flow paths, active transfers,
 * and background map/grid are drawn.
 */
@SuppressWarnings("serial")
public class PlayField extends JPanel {
	public static final int PLAYFIELD_OFFSET = 15;

	private World w;
	private DTNSimGUI gui;

	private Color bgColor = new Color(250, 252, 255);
	private Color gridColor = new Color(225, 232, 240);
	private Color gridLabelColor = new Color(160, 175, 195);
	private Font gridFont = new Font("SansSerif", Font.PLAIN, 9);

	private List<PlayFieldGraphic> overlayGraphics;
	private MessagePathGraphic activeMessagePathGraphic;
	private boolean autoClearOverlay;
	private MapGraphic mapGraphic;
	private boolean showMapGraphic;
	private ScaleReferenceGraphic refGraphic;
	private boolean focusOnClick;

	private BufferedImage underlayImage;
	private AffineTransform imageTransform;
	private AffineTransform curTransform;
	private double underlayImgDx;
	private double underlayImgDy;

	public PlayField(World w, DTNSimGUI gui) {
		this.w = w;
		this.gui = gui;

		this.refGraphic = new ScaleReferenceGraphic();
		updateFieldSize();
		this.setBackground(bgColor);
		this.overlayGraphics = Collections.synchronizedList(new ArrayList<PlayFieldGraphic>());
		this.activeMessagePathGraphic = null;
		this.mapGraphic = null;
		this.underlayImage = null;
		this.imageTransform = null;
		this.autoClearOverlay = true;

		this.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (focusOnClick) {
					focusClosestNode(e.getX(), e.getY());
				}
			}
		});
	}

	public void updateField() {
		this.repaint();
	}

	public void setUnderlayImage(BufferedImage image,
			double dx, double dy, double scale, double rotation) {
		if (image == null) {
			this.underlayImage = null;
			this.imageTransform = null;
			this.curTransform = null;
			return;
		}
		this.underlayImage = image;
		this.imageTransform = AffineTransform.getRotateInstance(rotation);
		this.imageTransform.scale(scale, scale);
		this.curTransform = new AffineTransform(imageTransform);
		this.underlayImgDx = dx;
		this.underlayImgDy = dy;

		curTransform.scale(PlayFieldGraphic.getScale(), PlayFieldGraphic.getScale());
		curTransform.translate(this.underlayImgDx, this.underlayImgDy);
	}

	public void setScale(double scale) {
		PlayFieldGraphic.setScale(scale);
		this.updateFieldSize();
		if (this.imageTransform != null) {
			this.curTransform = new AffineTransform(imageTransform);
			curTransform.scale(scale, scale);
			curTransform.translate(this.underlayImgDx, this.underlayImgDy);
		}
	}

	public void setMap(SimMap simMap) {
		this.mapGraphic = new MapGraphic(simMap);
		this.showMapGraphic = true;
	}

	public void setShowMapGraphic(boolean show) {
		this.showMapGraphic = show;
	}

	public void setAutoClearOverlay(boolean clear) {
		this.autoClearOverlay = clear;
	}

	public void setFocusOnClick(boolean focus) {
		this.focusOnClick = focus;
	}

	@Override
	public void paint(Graphics g) {
		paintComponent(g);
	}

	@Override
	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g;

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		g2.setColor(bgColor);
		g2.fillRect(0, 0, getWidth(), getHeight());

		g2.translate(PLAYFIELD_OFFSET, PLAYFIELD_OFFSET);

		drawGrid(g2);

		if (underlayImage != null) {
			g2.drawImage(underlayImage, curTransform, null);
		}

		if (mapGraphic != null && showMapGraphic) {
			mapGraphic.draw(g2);
		}

		for (DTNHost h : w.getHosts()) {
			new NodeGraphic(h).draw(g2);
		}

		synchronized (overlayGraphics) {
			for (int i = 0, n = overlayGraphics.size(); i < n; i++) {
				overlayGraphics.get(i).draw(g2);
			}
		}

		if (activeMessagePathGraphic != null) {
			activeMessagePathGraphic.draw(g2);
		}

		this.refGraphic.draw(g2);
	}

	private void drawGrid(Graphics2D g2) {
		int worldWidth = w.getSizeX();
		int worldHeight = w.getSizeY();
		int gridStep = 50;

		g2.setColor(gridColor);
		g2.setStroke(new BasicStroke(1.0f));

		for (int x = 0; x <= worldWidth; x += gridStep) {
			int sx = PlayFieldGraphic.scale(x);
			g2.drawLine(sx, 0, sx, PlayFieldGraphic.scale(worldHeight));
		}

		for (int y = 0; y <= worldHeight; y += gridStep) {
			int sy = PlayFieldGraphic.scale(y);
			g2.drawLine(0, sy, PlayFieldGraphic.scale(worldWidth), sy);
		}

		g2.setFont(gridFont);
		g2.setColor(gridLabelColor);
		for (int x = 0; x <= worldWidth; x += 100) {
			int sx = PlayFieldGraphic.scale(x);
			g2.drawString(x + "m", sx + 2, 10);
		}
		for (int y = 0; y <= worldHeight; y += 100) {
			int sy = PlayFieldGraphic.scale(y);
			g2.drawString(y + "m", 2, sy - 2);
		}
	}

	public void clearOverlays() {
		this.overlayGraphics.clear();
	}

	public void showMessagePath(Message m) {
		if (m == null) {
			this.activeMessagePathGraphic = null;
		} else {
			this.activeMessagePathGraphic = new MessagePathGraphic(m);
		}
		this.updateField();
	}

	public void clearMessagePath() {
		this.activeMessagePathGraphic = null;
		this.updateField();
	}

	public void addMessageTransfer(DTNHost from, DTNHost to) {
		addMessageTransfer(from, to, null);
	}

	public void addMessageTransfer(DTNHost from, DTNHost to, Message message) {
		autoClear();
		this.overlayGraphics.add(new MessageGraphic(from, to, message));
		this.updateField();
	}

	public void addPath(Path path) {
		autoClear();
		this.overlayGraphics.add(new PathGraphic(path));
		this.updateField();
	}

	private void autoClear() {
		if (this.autoClearOverlay) {
			this.clearOverlays();
		}
	}

	public Coord getGraphicsPosition(Coord loc) {
		Coord c = loc.clone();
		c.setLocation(PlayFieldGraphic.scale(c.getX()) + PLAYFIELD_OFFSET,
				PlayFieldGraphic.scale(c.getY()) + PLAYFIELD_OFFSET);
		return c;
	}

	public Coord getWorldPosition(Coord loc) {
		Coord c = loc.clone();
		c.setLocation(PlayFieldGraphic.invScale(c.getX() - PLAYFIELD_OFFSET),
				PlayFieldGraphic.invScale(c.getY() - PLAYFIELD_OFFSET));
		return c;
	}

	private void updateFieldSize() {
		Dimension minSize = new Dimension(
				PlayFieldGraphic.scale(w.getSizeX()) + PLAYFIELD_OFFSET * 2,
				PlayFieldGraphic.scale(w.getSizeY()) + PLAYFIELD_OFFSET * 2);
		this.setMinimumSize(minSize);
		this.setPreferredSize(minSize);
		this.setSize(minSize);
	}

	private void focusClosestNode(int x, int y) {
		if (w.getHosts().isEmpty()) return;
		DTNHost closest = w.getHosts().get(0);
		double closestDist = Double.MAX_VALUE;
		double dist;

		Coord clickLoc = getWorldPosition(new Coord(x, y));

		for (DTNHost h : w.getHosts()) {
			dist = h.getLocation().distance(clickLoc);

			if (dist < closestDist) {
				closest = h;
				closestDist = dist;
			}
		}

		gui.setFocus(closest);
	}
}
