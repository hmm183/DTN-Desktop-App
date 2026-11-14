/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;

import gui.nodefilter.*;
import gui.playfield.NodeGraphic;
import core.DTNHost;
import core.Settings;

/**
 * Modernized Node chooser panel with group-aware styled node buttons.
 */
@SuppressWarnings("serial")
public class NodeChooser extends JPanel implements ActionListener {
	private DTNSimGUI gui;
	public static final int MAX_NODE_COUNT = 500;
	private Timer refreshTimer;
	private static final int AUTO_REFRESH_DELAY = 100;

	public static final String NODE_MESSAGE_FILTERS_S = "nodeMessageFilters";

	private static final String HOST_KEY = "host";
	private static final Font FONT_NODE_BTN = new Font("SansSerif", Font.BOLD, 11);

	private List<DTNHost> allNodes;
	private List<DTNHost> shownNodes;

	private JComboBox<String> groupChooser;
	private JPanel nodesPanel;
	private JPanel chooserPanel;
	private Vector<NodeFilter> filters;

	public NodeChooser(List<DTNHost> nodes, DTNSimGUI gui) {
		Settings s = new Settings(MainWindow.GUI_NS);
		this.allNodes = new ArrayList<DTNHost>(nodes);
		this.shownNodes = allNodes;
		this.gui = gui;
		this.filters = new Vector<NodeFilter>();

		if (s.contains(NODE_MESSAGE_FILTERS_S)) {
			String[] filterIds = s.getCsvSetting(NODE_MESSAGE_FILTERS_S);
			for (String id : filterIds) {
				this.filters.add(new NodeMessageFilter(id));
				this.refreshTimer = new Timer(AUTO_REFRESH_DELAY, this);
				this.refreshTimer.start();
			}
		}

		Collections.sort(this.allNodes);
		init();
	}

	public void addFilter(NodeFilter f) {
		this.filters.add(f);
		updateShownNodes();
		if (this.refreshTimer == null) {
			this.refreshTimer = new Timer(AUTO_REFRESH_DELAY, this);
			this.refreshTimer.start();
		}
	}

	public void clearFilters() {
		this.filters = new Vector<NodeFilter>();
		this.shownNodes = allNodes;
		if (this.refreshTimer != null) {
			this.refreshTimer.stop();
		}
		this.refreshTimer = null;

		NodeGraphic.setHighlightedNodes(null);
		updateList();
	}

	private void updateList() {
		setNodes(0);
		if (this.groupChooser != null) {
			this.groupChooser.setSelectedIndex(0);
		}
	}

	private void updateShownNodes() {
		List<DTNHost> oldShownNodes = shownNodes;
		List<DTNHost> nodes = new Vector<DTNHost>();

		for (DTNHost node : allNodes) {
			for (NodeFilter f : this.filters) {
				if (f.filterNode(node)) {
					nodes.add(node);
					break;
				}
			}
		}

		if (nodes.size() == oldShownNodes.size() && oldShownNodes.containsAll(nodes)) {
			return;
		} else {
			this.shownNodes = nodes;
			updateList();
			NodeGraphic.setHighlightedNodes(nodes);
		}
	}

	private void init() {
		nodesPanel = new JPanel();
		chooserPanel = new JPanel();

		this.setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.FIRST_LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;

		nodesPanel.setLayout(new BoxLayout(nodesPanel, BoxLayout.Y_AXIS));
		nodesPanel.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(new Color(200, 210, 220), 1),
				"Simulation Nodes (" + allNodes.size() + ")"));

		if (shownNodes.size() > MAX_NODE_COUNT) {
			String[] groupNames = new String[(shownNodes.size() - 1) / MAX_NODE_COUNT + 1];
			int last = 0;
			for (int i = 0, n = shownNodes.size(); i <= (n - 1) / MAX_NODE_COUNT; i++) {
				int next = MAX_NODE_COUNT * (i + 1) - 1;
				if (next > n) {
					next = n - 1;
				}
				groupNames[i] = (last + "..." + next);
				last = next + 1;
			}
			groupChooser = new JComboBox<String>(groupNames);
			groupChooser.addActionListener(this);
			chooserPanel.add(groupChooser);
		}

		setNodes(0);
		c.gridy = 0;
		this.add(chooserPanel, c);
		c.gridy = 1;
		this.add(nodesPanel, c);
	}

	private void setNodes(int offset) {
		nodesPanel.removeAll();

		for (int i = offset; i < shownNodes.size() && i < offset + MAX_NODE_COUNT; i++) {
			DTNHost h = shownNodes.get(i);
			JButton jb = createStyledNodeButton(h);
			nodesPanel.add(jb);
		}

		revalidate();
		repaint();
	}

	private JButton createStyledNodeButton(DTNHost h) {
		JButton jb = new JButton(h.toString());
		jb.putClientProperty(HOST_KEY, h);
		jb.addActionListener(this);
		jb.setFont(FONT_NODE_BTN);
		jb.setCursor(new Cursor(Cursor.HAND_CURSOR));
		jb.setFocusPainted(false);
		jb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		String name = h.toString().toLowerCase();
		Color fgCol = new Color(24, 43, 73);
		Color bgCol = new Color(240, 245, 252);
		Color borderCol = new Color(190, 205, 225);

		if (name.startsWith("p")) {
			bgCol = new Color(235, 245, 255);
			borderCol = new Color(150, 195, 240);
		} else if (name.startsWith("c")) {
			bgCol = new Color(254, 243, 230);
			borderCol = new Color(245, 185, 120);
		}

		jb.setBackground(bgCol);
		jb.setForeground(fgCol);
		jb.setBorder(new CompoundBorder(
				BorderFactory.createLineBorder(borderCol, 1),
				new EmptyBorder(3, 8, 3, 8)));
		jb.setToolTipText("Focus node " + h.toString() + " at " + h.getLocation());
		return jb;
	}

	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JButton) {
			JButton source = (JButton) e.getSource();
			DTNHost host = (DTNHost) source.getClientProperty(HOST_KEY);
			if (host != null) {
				gui.setFocus(host);
			}
		} else if (e.getSource() == this.groupChooser) {
			setNodes(groupChooser.getSelectedIndex() * MAX_NODE_COUNT);
		} else if (e.getSource() == this.refreshTimer) {
			updateShownNodes();
		}
	}
}
